#!/usr/bin/env bash
# Load test of the whole application over HTTP: how many requests per second, and how many users at once, it carries
# while p95 stays under 500 ms and under 1% of requests fail. Method and results: docs/benchmarks.md.
#
#   loadtest/run.sh [smoke|read|book|mixed|users|all]      (default: all; needs Docker, k6, openssl, jq, curl)
#   loadtest/run.sh concurrency      highest request rate and most requests open at once, 70% reads and 30% bookings;
#       knobs: LEVELS="10 25 ..." ROUNDS LEVEL_SECONDS BOOK_SHARE WARM_MIN WARM_MAX
#
# Knobs (environment): RATES="100 200 ..." USER_LEVELS="1000 2000 ..." WARMUP STEP CONFIRM RAMP (seconds),
# POOL (database connections) APP_CPUS DB_CPUS, KEEP=1 leaves the stack running afterwards.
#   APP_REPLICAS=2 runs that many instances behind nginx (compose.scale.yaml, PROXY_CPUS); each has APP_CPUS and its own
#   pool. Without it there is one instance and nothing in front of it.
set -euo pipefail

# A machine that goes to sleep ruins a run (the clocks jump and the tokens expire), so sleep is held off until the
# script ends. The screen may still switch off and lock.
if [[ -z ${LOADTEST_AWAKE:-} ]] && systemd-inhibit --what=sleep --why="load test" true 2>/dev/null; then
    LOADTEST_AWAKE=1 exec systemd-inhibit --what=sleep --who="load test" --why="a load test is running" "$0" "$@"
fi
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

# concurrency profile
LEVELS=${LEVELS:-10 25 50 100 200 400 800 1600 3200 6400 9600}
ROUNDS=${ROUNDS:-2}
LEVEL_SECONDS=${LEVEL_SECONDS:-60}
BOOK_SHARE=${BOOK_SHARE:-0.3}
WARM_CLIENTS=${WARM_CLIENTS:-50}
WARM_MIN=${WARM_MIN:-3}
WARM_MAX=${WARM_MAX:-6}
COMPOSE_EXTRA=
METRICS_URLS=()
SERVER_ROWS=
APP_REPLICAS=${APP_REPLICAS:-}
if [[ -n $APP_REPLICAS ]]; then export APP_REPLICAS; fi

compose() {
    docker compose -f loadtest/compose.yaml ${COMPOSE_EXTRA:+-f "$COMPOSE_EXTRA"} \
        ${APP_REPLICAS:+-f loadtest/compose.scale.yaml} "$@"
}
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
        compose logs --no-color -t app database ${APP_REPLICAS:+proxy} >"$OUT/logs/$(date +%Y%m%d-%H%M%S).log" 2>&1 || true
    fi
}

container_ip() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$@"; }

# wait_for <url of an instance or of the proxy>
wait_for() {
    local attempt
    for attempt in $(seq 120); do
        if curl -sf -o /dev/null --max-time 2 "$1/v3/api-docs"; then return; fi
        sleep 1
    done
    compose logs --tail 50 app ${APP_REPLICAS:+proxy} >&2
    die "the application did not start"
}

query() { compose exec -T database psql -At -F ' ' -v ON_ERROR_STOP=1 -U bidtix -d bidtix "$@"; }

# A new database, new keys and tokens, the application started and the data loaded.
reset_stack() {
    save_logs
    compose down -v --remove-orphans >/dev/null 2>&1
    loadtest/tokens.sh "$OUT" "$USERS"
    compose up -d >/dev/null 2>&1 || { compose logs --tail 50 >&2; die "the stack did not start"; }

    local ips ip
    # shellcheck disable=SC2046
    mapfile -t ips < <(container_ip $(compose ps -q app))
    [[ ${#ips[@]} == "${APP_REPLICAS:-1}" ]] || die "expected ${APP_REPLICAS:-1} instances, found ${#ips[@]}"
    METRICS_URLS=()
    for ip in "${ips[@]}"; do
        wait_for "http://$ip:8080"
        if [[ -n $COMPOSE_EXTRA ]]; then METRICS_URLS+=("http://$ip:9001"); fi
    done
    # straight to a container: Docker's port proxy would be one more thing competing for the CPU
    BASE_URL=http://${ips[0]}:8080
    if [[ -n $APP_REPLICAS ]]; then
        BASE_URL=http://$(container_ip "$(compose ps -q proxy)"):8080
        wait_for "$BASE_URL"
    fi
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
            if ((${#METRICS_URLS[@]})); then sample_app >>"$base.stats"; fi
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

# metric <instance's metrics URL> <name> <statistic>: one figure of that instance's own metrics; nothing when it cannot
# be had within two seconds.
metric() {
    curl -sf --max-time 2 -H "Authorization: Bearer $(jq -r '.[0]' "$OUT/tokens.json")" "$1/actuator/metrics/$2" |
        jq -r --arg s "$3" '.measurements[] | select(.statistic == $s) | .value' || true
}

# lines "m <name> <seconds since 1970> <value>": busy request threads, database connections in use and waited for,
# and the running totals of how often and how long connections were held and waited for. Added up over the instances;
# a figure that one of them did not give is left out, because a partial sum would look like a drop.
sample_app() {
    local spec name statistic value url values
    for spec in tomcat.threads.busy:VALUE hikaricp.connections.active:VALUE hikaricp.connections.pending:VALUE \
        hikaricp.connections.usage:COUNT hikaricp.connections.usage:TOTAL_TIME \
        hikaricp.connections.acquire:COUNT hikaricp.connections.acquire:TOTAL_TIME; do
        name=${spec%:*} statistic=${spec#*:}
        values=()
        for url in "${METRICS_URLS[@]}"; do
            value=$(metric "$url" "$name" "$statistic")
            if [[ -n $value ]]; then values+=("$value"); fi
        done
        if ((${#values[@]} == ${#METRICS_URLS[@]})); then
            value=$(printf '%s\n' "${values[@]}" | awk '{sum += $1} END {printf "%.6f", sum}')
            echo "m $name:$statistic $(date +%s.%N) $value"
        fi
    done
}

# how many requests each instance has answered since it started: shows that the load balancer really spread them
requests_per_instance() {
    local url counts=()
    for url in "${METRICS_URLS[@]}"; do
        counts+=("$(metric "$url" http.server.requests COUNT | awk '{printf "%.0f", $1}')")
    done
    echo "${counts[*]}"
}

# average CPU of one service over a run, in percent of one core (200% = two cores fully used); the instances of a
# service that has several are added up
cpu_of() {
    awk -v name="-$2-" 'index($1, name) {sub("%", "", $2); sum[$1] += $2; n[$1]++}
        END {for (c in n) {total += sum[c] / n[c]; found = 1}; if (found) printf "%.0f%%", total; else printf "-"}' "$1"
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

# the application's side of one level, from the "m" lines of its samples
server_figures() {
    awk '$1 == "m" {
            k = $2; if (!(k in first)) {first[k] = $4; at[k] = $3}
            last[k] = $4; to[k] = $3; if ($4 + 0 > max[k]) max[k] = $4 + 0
        }
        function per(total, count,   c) {c = last[count] - first[count]; return c > 0 ? sprintf("%.1f", (last[total] - first[total]) / c * 1000) : "-"}
        END {
            u = "hikaricp.connections.usage:TOTAL_TIME"; span = to[u] - at[u]
            busy = "tomcat.threads.busy:VALUE"; act = "hikaricp.connections.active:VALUE"; pen = "hikaricp.connections.pending:VALUE"
            printf "%s | %s | %s | %s | %s | %s",
                (busy in max) ? max[busy] + 0 : "-", (act in max) ? max[act] + 0 : "-", (pen in max) ? max[pen] + 0 : "-",
                per(u, "hikaricp.connections.usage:COUNT"),
                per("hikaricp.connections.acquire:TOTAL_TIME", "hikaricp.connections.acquire:COUNT"),
                (span > 0 ? sprintf("%.1f", (last[u] - first[u]) / span) : "-")
        }' "$1"
}

# level <label> <concurrency> <seconds>: one measured level, a row for the client and one for the server.
# Succeeds when the good mark held; LEVEL_FILE names its result, empty when there is none.
LEVEL_FILE=
level() {
    local label=$1 clients=$2 seconds=$3
    local summary=$OUT/runs/concurrency-$ROUND-$clients-$label.json status=0
    LEVEL_FILE=
    sleep "$COOLDOWN"
    run_k6 "$summary" 10 MODE=concurrency PROFILE=mixed BOOK_SHARE="$BOOK_SHARE" CONCURRENCY="$clients" \
        RAMP=10 DURATION="$seconds" || status=$?
    if ((status == 2)) || [[ ! -s $summary ]]; then
        record "| $label | $clients | no result: $( ((status == 2)) && echo "the load generator ran short of memory" || echo "k6 failed, see ${summary%.json}.log") |"
        return 1
    fi
    LEVEL_FILE=$summary
    local stats=${summary%.json}.stats
    record "$(jq -r --arg label "$label" '
        def pct: (. * 10000 | round) / 100 | "\(.)%";
        "| \($label) | \(.target) | \(.rps | round) | \(.avg | round) | \(.p95 | round) | \(.p99 | round) | " +
        "\(.readP95 | round) | \(.bookP95 | round) | \(.failed | pct) | \(.timeouts) | \(.open | round) | " +
        "\(if .pass then "good" elif .broken then "**broken**" else "slow" end) |"' "$summary")"
    SERVER_ROWS+="| $label | $clients | $(server_figures "$stats") | $(cpu_of "$stats" app) | $(cpu_of "$stats" database) | $(cpu_of "$stats" cache) | $(cpu_of "$stats" proxy) | $(awk '$1 == "k6" {cpu = $2} END {printf "%.0f%%", cpu}' "$stats") | $(awk '$1 == "k6" && $3 > max {max = $3} END {printf "%.0f MB", max / 1024}' "$stats") |"$'\n'
    jq -e .pass "$summary" >/dev/null
}

# Load that no longer changes the result: one-minute runs until the rate stops climbing by 10% or more.
warm_until_steady() {
    local round rate previous=0 rates=()
    for round in $(seq "$WARM_MAX"); do
        say "warm-up $round: $WARM_CLIENTS requests at once"
        run_k6 "$OUT/runs/warmup.json" 0 MODE=concurrency PROFILE=mixed BOOK_SHARE="$BOOK_SHARE" \
            CONCURRENCY="$WARM_CLIENTS" RAMP=5 DURATION="$LEVEL_SECONDS" || true
        rate=$(jq '.rps | round' "$OUT/runs/warmup.json")
        rates+=("$rate")
        if ((round >= WARM_MIN && previous > 0)) &&
            awk -v a="$rate" -v b="$previous" 'BEGIN {exit !((a - b < 0 ? b - a : a - b) / b < 0.1)}'; then break; fi
        previous=$rate
    done
    record "Warm-up, not counted: ${#rates[@]} runs at $WARM_CLIENTS requests at once, req/s ${rates[*]}."
}

# Highest rate and most requests open at once: climb until it breaks, look between, hold the best good level.
concurrency_round() {
    local clients good=0 slow=0 broke=0 files=()
    say "concurrency, round $ROUND: fresh stack"
    reset_stack
    record ""
    record "## Round $ROUND, started $(date '+%H:%M:%S')"
    record ""
    warm_until_steady
    SERVER_ROWS=
    record ""
    record "| run | open requests | req/s | avg ms | p95 ms | p99 ms | read p95 | book p95 | failed | timeouts | req/s x avg | mark |"
    record "|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|"
    for clients in $LEVELS; do
        say "round $ROUND: $clients at once"
        if level ladder "$clients" "$LEVEL_SECONDS"; then
            if ((!slow)); then good=$clients; fi
        elif ((!slow)); then
            slow=$clients
        fi
        [[ -n $LEVEL_FILE ]] || break
        files+=("$LEVEL_FILE")
        if jq -e .broken "$LEVEL_FILE" >/dev/null; then
            broke=$clients
            break
        fi
    done
    if ((good && slow && slow - good > 10)); then
        clients=$(((good + slow) / 2))
        if level between "$clients" "$LEVEL_SECONDS"; then good=$clients; fi
        if [[ -n $LEVEL_FILE ]]; then files+=("$LEVEL_FILE"); fi
    fi
    local held="no"
    if ((good)); then
        if level confirm "$good" "$CONFIRM"; then held="yes"; fi
        if [[ -n $LEVEL_FILE ]]; then files+=("$LEVEL_FILE"); fi
    fi

    record ""
    record "Server side (CPU: 100% = one core; connections: pool of ${POOL:-10} per instance, figures added up over ${APP_REPLICAS:-1})."
    record ""
    record "| run | open requests | busy threads (max) | connections in use (max) | waiting for one (max) | held ms | waited ms | connections busy on average | app CPU | db CPU | redis CPU | nginx CPU | k6 CPU | k6 RAM |"
    record "|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|"
    record "${SERVER_ROWS%$'\n'}"
    check_stock
    record ""
    record "Requests answered by each instance since it started: $(requests_per_instance)."
    record ""
    if ((${#files[@]})); then
        record "$(jq -rs '[.[] | select(.broken | not)] | max_by(.rps) // empty | "**Highest rate without breaking: \(.rps | round) req/s**, with \(.target) requests open, p95 \(.p95 | round) ms."' "${files[@]}")"
    fi
    record "**Most requests open at once with p95 < 500 ms and < 1% failed: $good** (held for ${CONFIRM}s: $held)."
    if ((broke)); then
        record "**Broke (1% or more failed) at $broke requests open.**"
    else
        record "**Never broke; the highest level tried is in the table.**"
    fi
    record "Round $ROUND ended $(date '+%H:%M:%S')."
}

concurrency() {
    record "- Traffic: $(awk -v b="$BOOK_SHARE" 'BEGIN {printf "%.0f%% reads, %.0f%% bookings", (1 - b) * 100, b * 100}'); every client sends its next request when the answer arrives; levels: $LEVELS; ${LEVEL_SECONDS}s each after 10s of arriving"
    record "- Marks: good = p95 < 500 ms and < 1% failed; broken = 1% or more failed (a request times out after 10 s)"
    for ((ROUND = 1; ROUND <= ROUNDS; ROUND++)); do
        concurrency_round
    done
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
    concurrency) COMPOSE_EXTRA=loadtest/compose.metrics.yaml ;;
    *) die "usage: loadtest/run.sh [smoke|read|book|mixed|users|concurrency|all]" ;;
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
record "- $(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo), $(nproc) threads; ${APP_REPLICAS:-1} x app ${APP_CPUS:-2} CPU / 2 GB$([[ -n $APP_REPLICAS ]] && echo " behind nginx ${PROXY_CPUS:-1} CPU"), PostgreSQL ${DB_CPUS:-2} CPU / 1 GB, Redis 1 CPU; pool ${POOL:-10}"
record "- $(k6 version | head -1 | awk '{print $1, $2}') on the same machine$([[ $what == concurrency ]] || echo '; pass = p95 < 500 ms, failed < 1%, dropped < 1%')"
record "- Other containers running during the test: ${others:-none}"

case $what in
    smoke) smoke ;;
    read | book | mixed) ladder "$what" ;;
    users) users_ladder ;;
    concurrency) concurrency ;;
    all)
        ladder read
        ladder book
        ladder mixed
        users_ladder
        ;;
esac
say "result: $RESULTS"
