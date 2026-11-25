#!/usr/bin/env bash
# Load test of the whole application over HTTP: how many requests per second, and how many users at once, it carries
# while p95 stays under 500 ms and under 1% of requests fail. Method and results: docs/benchmarks.md.
#
#   loadtest/run.sh [smoke|read|book|mixed|users|all]      (default: all; needs Docker, k6, openssl, jq, curl)
#
# Knobs (environment): RATES="100 200 ..." USER_LEVELS="1000 2000 ..." WARMUP STEP CONFIRM RAMP (seconds),
# POOL (database connections) APP_CPUS DB_CPUS, KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=target/loadtest
USERS=${USERS:-5000}
RATES=${RATES:-100 200 400 800 1200 1600 2400 3200 4800}
USER_LEVELS=${USER_LEVELS:-1000 2000 3000 5000}
WARMUP=${WARMUP:-60}
WARMUP_RATE=${WARMUP_RATE:-100}
STEP=${STEP:-60}
CONFIRM=${CONFIRM:-180}
RAMP=${RAMP:-30}
COOLDOWN=${COOLDOWN:-15}
# stop a run rather than let the load generator push the machine into swap
MIN_FREE_MB=${MIN_FREE_MB:-1500}
HOT_ZONE=00000000-0000-4000-a000-000000000001
BASE_URL=
K6_PID=
export K6_NO_USAGE_REPORT=true

RESULTS=$OUT/results-$(date +%Y%m%d-%H%M%S).md

compose() { docker compose -f loadtest/compose.yaml "$@"; }
say() { printf '\n== %s\n' "$*" >&2; }
die() { echo "loadtest: $*" >&2; exit 1; }
record() { printf '%s\n' "$*" | tee -a "$RESULTS"; }

cleanup() {
    if [[ -n $K6_PID ]]; then kill "$K6_PID" 2>/dev/null || true; fi
    save_logs
    if [[ ${KEEP:-0} != 1 ]]; then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
}

# the application's and the database's logs of the stack that is about to go away
save_logs() {
    if [[ -n $(compose ps -q app 2>/dev/null) ]]; then
        mkdir -p "$OUT/logs"
        compose logs --no-color -t app database >"$OUT/logs/$(date +%Y%m%d-%H%M%S).log" 2>&1 || true
    fi
}

query() { compose exec -T database psql -At -F ' ' -v ON_ERROR_STOP=1 -U bidtix -d bidtix "$@"; }

# A new database, new keys and tokens, the application started and the data loaded.
reset_stack() {
    save_logs
    compose down -v --remove-orphans >/dev/null 2>&1
    loadtest/tokens.sh "$OUT" "$USERS"
    compose up -d >/dev/null 2>&1 || { compose logs --tail 50 >&2; die "the stack did not start"; }

    local ip
    ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(compose ps -q app)")
    # straight to the container: Docker's port proxy would be one more thing competing for the CPU
    BASE_URL=http://$ip:8080
    local attempt
    for attempt in $(seq 120); do
        if curl -sf -o /dev/null --max-time 2 "$BASE_URL/v3/api-docs"; then break; fi
        if [[ $attempt == 120 ]]; then compose logs --tail 50 app >&2; die "the application did not start"; fi
        sleep 1
    done
    query -q -v users="$USERS" -f - <loadtest/seed.sql >/dev/null
}

# run_k6 <result.json> <seconds to leave out of the CPU figures> <NAME=value>...
# Runs k6 and samples CPU and memory next to it. Returns 2 when it had to be stopped for lack of memory.
run_k6() {
    local summary=$1 skip=$2
    shift 2
    local base=${summary%.json} args=() pair starved=0 started=$SECONDS
    for pair in "$@"; do args+=(-e "$pair"); done
    mkdir -p "$(dirname "$summary")"
    rm -f "$summary"
    : >"$base.stats"

    k6 run --quiet "${args[@]}" -e BASE_URL="$BASE_URL" -e TOKENS="$PWD/$OUT/tokens.json" \
        -e SUMMARY="$PWD/$summary" loadtest/script.js >"$base.log" 2>&1 &
    K6_PID=$!
    local containers
    containers=$(compose ps -q)
    while kill -0 "$K6_PID" 2>/dev/null; do
        if ((SECONDS - started >= skip)); then
            # shellcheck disable=SC2086
            docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' $containers >>"$base.stats"
            ps -o %cpu=,rss= -p "$K6_PID" | awk '{print "k6", $1, $2}' >>"$base.stats" || true
        fi
        if (($(awk '/MemAvailable/ {print int($2 / 1024)}' /proc/meminfo) < MIN_FREE_MB)); then
            starved=1
            kill "$K6_PID" 2>/dev/null || true
        fi
        sleep 1
    done
    # k6 exits non-zero when a threshold is crossed; the result file says what happened
    wait "$K6_PID" || true
    K6_PID=
    if ((starved)); then return 2; fi
}

# average CPU of one container over a run, in percent of one core (200% = two cores fully used)
cpu_of() {
    awk -v name="-$2-" 'index($1, name) {sub("%", "", $2); sum += $2; n++} END {if (n) printf "%.0f%%", sum / n; else printf "-"}' "$1"
}

table_header() {
    record ""
    record "| run | target | req/s | p50 ms | p95 ms | p99 ms | failed | dropped | pass | app CPU | db CPU | redis CPU | k6 CPU | k6 RAM |"
    record "|---|--:|--:|--:|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|"
}

# step <label> <rate|users> <profile> <target> <seconds>: one measured run, one table row. Succeeds when it passed.
step() {
    local label=$1 mode=$2 profile=$3 target=$4 seconds=$5
    local summary=$OUT/runs/$profile-$mode-$target-$label.json
    local skip=0 status=0 extra=(RATE="$target")
    if [[ $mode == users ]]; then
        skip=$RAMP
        extra=(USERS="$target" RAMP="$RAMP")
    fi
    sleep "$COOLDOWN"
    run_k6 "$summary" "$skip" MODE="$mode" PROFILE="$profile" DURATION="$seconds" "${extra[@]}" || status=$?
    if ((status == 2)); then
        record "| $profile $label | $target | stopped: less than $MIN_FREE_MB MB of memory left for the load generator |"
        return 1
    fi
    [[ -s $summary ]] || { tail -20 "${summary%.json}.log" >&2; die "k6 produced no result"; }

    local stats=${summary%.json}.stats k6_cpu k6_ram
    k6_cpu=$(awk '$1 == "k6" {cpu = $2} END {if (cpu != "") printf "%.0f%%", cpu; else printf "-"}' "$stats")
    k6_ram=$(awk '$1 == "k6" && $3 > max {max = $3} END {printf "%.0f MB", max / 1024}' "$stats")
    record "$(jq -r --arg label "$label" --arg app "$(cpu_of "$stats" app)" --arg db "$(cpu_of "$stats" database)" \
        --arg redis "$(cpu_of "$stats" cache)" --arg k6 "$k6_cpu" --arg ram "$k6_ram" '
        def pct: (. * 10000 | round) / 100 | "\(.)%";
        "| \(.profile) \($label) | \(.target) | \(.rps | round) | \(.p50 | round) | \(.p95 | round) | \(.p99 | round) | " +
        "\(.failed | pct) | \(.dropped | pct) | \(if .pass then "yes" else "**no**" end) | \($app) | \($db) | \($redis) | " +
        "\($k6) | \($ram) |"' "$summary")"
    jq -e .pass "$summary" >/dev/null
}

warm_up() {
    say "warm-up: ${WARMUP}s at $WARMUP_RATE req/s, not recorded"
    run_k6 "$OUT/runs/warmup.json" 0 MODE=rate PROFILE="$1" DURATION="$WARMUP" RATE="$WARMUP_RATE" || true
}

# every ticket taken from the hot zone belongs to a live order, and the other way round
check_stock() {
    sleep 5
    local taken ordered
    read -r taken ordered <<<"$(query -c "
        select z.capacity - z.available_quantity,
               coalesce(sum(i.quantity) filter (where o.status <> 'CANCELLED'), 0)
        from ticket_zones z
        left join order_items i on i.zone_id = z.id
        left join orders o on o.id = i.order_id
        where z.public_id = '$HOT_ZONE'
        group by z.id")"
    [[ -n $taken && $taken == "$ordered" ]] || die "STOCK MISMATCH: $taken taken from the zone, $ordered in orders"
    record ""
    record "Stock check: $taken tickets taken from the hot zone, $ordered in orders."
}

# Highest request rate one profile carries: climb until a step fails, try the middle, then hold the best for longer.
ladder() {
    local profile=$1 rate passed=() failed=0
    say "$profile: fresh stack"
    reset_stack
    warm_up "$profile"
    record ""
    record "## Requests per second: $profile"
    table_header
    for rate in $RATES; do
        if step ladder rate "$profile" "$rate" "$STEP"; then
            passed=("$rate" "${passed[@]}")
        else
            failed=$rate
            break
        fi
    done
    if ((failed && ${#passed[@]} && failed - ${passed[0]:-0} > 100)); then
        rate=$(((failed + passed[0]) / 2))
        if step between rate "$profile" "$rate" "$STEP"; then passed=("$rate" "${passed[@]}"); fi
    fi

    local confirmed=0
    for rate in "${passed[@]:0:3}"; do
        if step confirm rate "$profile" "$rate" "$CONFIRM"; then
            confirmed=$rate
            break
        fi
    done
    if [[ $profile != read ]]; then check_stock; fi
    record ""
    if ((!confirmed)); then
        record "**$profile: no rate was held for ${CONFIRM}s.**"
    elif ((!failed)); then
        record "**$profile: held $confirmed req/s for ${CONFIRM}s. No step failed, so the limit is higher.**"
    else
        record "**$profile: held $confirmed req/s for ${CONFIRM}s (first failing step: $failed req/s).**"
    fi
}

# Most users at once, mixed traffic, each waiting 1-3 s between requests.
users_ladder() {
    local level held=0 failed=0
    record ""
    record "## Users at once (mixed traffic, 1-3 s between a user's requests)"
    for level in $USER_LEVELS; do
        say "users: $level, fresh stack"
        reset_stack
        warm_up mixed
        local ok=1
        table_header
        step users users mixed "$level" "$CONFIRM" || ok=0
        check_stock
        if ((ok)); then held=$level; else failed=$level; break; fi
    done
    record ""
    record "**Users at once: $held held for ${CONFIRM}s; first failing level: $([[ $failed == 0 ]] && echo none || echo "$failed").**"
}

smoke() {
    say "smoke: fresh stack"
    reset_stack
    # a JVM that has just started answers far slower than it will a moment later
    WARMUP=20 warm_up mixed
    table_header
    local profile
    for profile in read book mixed; do
        COOLDOWN=0 step smoke rate "$profile" 20 10 || die "smoke: $profile did not pass"
        [[ $(jq .failed "$OUT/runs/$profile-rate-20-smoke.json") == 0 ]] || die "smoke: $profile had failed requests"
    done
    local token refused=0 i
    token=$(jq -r '.[0]' "$OUT/tokens.json")
    for i in $(seq 100); do
        curl -sf -o /dev/null -H "Authorization: Bearer $token" "$BASE_URL/api/v1/events" || refused=$((refused + 1))
    done
    [[ $refused == 0 ]] || die "smoke: $refused of 100 quick requests from one user were refused"
    record ""
    record "100 quick requests from one user: none refused."
    check_stock
}

what=${1:-all}
case $what in
    smoke | read | book | mixed | users | all) ;;
    *) die "usage: loadtest/run.sh [smoke|read|book|mixed|users|all]" ;;
esac
for tool in docker k6 openssl jq curl basenc; do
    command -v "$tool" >/dev/null || die "$tool is not installed"
done

mkdir -p "$OUT"
say "building the application image"
compose build app >"$OUT/build.log" 2>&1 || { tail -40 "$OUT/build.log" >&2; die "the image did not build"; }
trap cleanup EXIT

others=$(docker ps --format '{{.Label "com.docker.compose.project"}} {{.Names}}' | awk '$1 != "bidtix-loadtest" {print $NF}' | paste -sd ' ')
record "# Load test, $(date '+%Y-%m-%d %H:%M')"
record ""
record "- Commit $(git rev-parse --short HEAD)$([[ -n $(git status --porcelain -- src pom.xml) ]] && echo ' with uncommitted changes in src or pom.xml')"
record "- $(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo), $(nproc) threads; app ${APP_CPUS:-2} CPU / 2 GB, PostgreSQL ${DB_CPUS:-2} CPU / 1 GB, Redis 1 CPU; pool ${POOL:-10}"
record "- $(k6 version | head -1 | awk '{print $1, $2}') on the same machine; pass = p95 < 500 ms, failed < 1%, dropped < 1%"
record "- Other containers running during the test: ${others:-none}"

case $what in
    smoke) smoke ;;
    read | book | mixed) ladder "$what" ;;
    users) users_ladder ;;
    all)
        ladder read
        ladder book
        ladder mixed
        users_ladder
        ;;
esac
say "result: $RESULTS"
