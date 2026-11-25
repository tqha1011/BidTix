#!/usr/bin/env bash
# "Sale day": a long run of people buying and bidding the way they would (realistic.js), then checks that the data is
# still right. Writes a report with the system minute by minute. Method and results: docs/benchmarks.md.
#
#   loadtest/soak.sh        (needs Docker, k6, openssl, jq, curl)
#
# Knobs (environment): MINUTES (30), BASE and PEAK shoppers (300, 1000), BIDDERS (150), POOL, APP_CPUS, DB_CPUS,
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail

# A machine that goes to sleep ruins a run (the clocks jump and the tokens expire), so sleep is held off until the
# script ends. The screen may still switch off and lock.
if [[ -z ${LOADTEST_AWAKE:-} ]] && systemd-inhibit --what=sleep --why="load test" true 2>/dev/null; then
    LOADTEST_AWAKE=1 exec systemd-inhibit --what=sleep --who="load test" --why="a load test is running" "$0" "$@"
fi
cd "$(dirname "$0")/.."

MINUTES=${MINUTES:-30}
BASE=${BASE:-300}
PEAK=${PEAK:-1000}
BIDDERS=${BIDDERS:-150}
USERS=${USERS:-5000}
SETTLE=${SETTLE:-90}
MIN_FREE_MB=${MIN_FREE_MB:-1500}
OUT=target/loadtest/soak/$(date +%Y%m%d-%H%M%S)
REPORT=$OUT/report.md
K6_PID=
SAMPLER_PID=
export K6_NO_USAGE_REPORT=true

compose() { docker compose -f loadtest/compose.yaml -f loadtest/compose.realistic.yaml "$@"; }
query() { compose exec -T database psql -At -F ' ' -v ON_ERROR_STOP=1 -U bidtix -d bidtix "$@"; }
say() { printf '== %s %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { echo "soak: $*" >&2; exit 1; }
out() { printf '%s\n' "$*" >>"$REPORT"; }

cleanup() {
    if [[ -n $SAMPLER_PID ]]; then kill "$SAMPLER_PID" 2>/dev/null || true; fi
    if [[ -n $K6_PID ]]; then kill "$K6_PID" 2>/dev/null || true; fi
    if [[ -n $(compose ps -q app 2>/dev/null) ]]; then
        compose logs --no-color -t app >"$OUT/app.log" 2>&1 || true
        compose logs --no-color -t database >"$OUT/database.log" 2>&1 || true
    fi
    if [[ ${KEEP:-0} != 1 ]]; then compose down -v --remove-orphans >/dev/null 2>&1 || true; fi
}

# one line every ten seconds or so: what the containers use and what the database is doing
sample() {
    local started=$SECONDS ids stats database free
    ids=$(compose ps -q)
    echo "second,app_cpu,app_mb,db_cpu,db_mb,redis_cpu,free_mb,db_active,db_lock_waits,pending,paid,cancelled" >"$OUT/samples.csv"
    while true; do
        # shellcheck disable=SC2086
        stats=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' $ids | awk '
            {sub("%", "", $2); mb = $3 + 0; if ($3 ~ /GiB/) mb *= 1024}
            /-app-/ {ac = $2; am = mb} /-database-/ {dc = $2; dm = mb} /-cache-/ {rc = $2}
            END {printf "%.0f,%.0f,%.0f,%.0f,%.0f", ac, am, dc, dm, rc}')
        database=$(query -F , -c "
            select (select count(*) from pg_stat_activity where datname = 'bidtix' and state = 'active'),
                   (select count(*) from pg_stat_activity where datname = 'bidtix' and wait_event_type = 'Lock'),
                   count(*) filter (where status = 'PENDING_PAYMENT'),
                   count(*) filter (where status = 'PAID'),
                   count(*) filter (where status = 'CANCELLED')
            from orders" 2>/dev/null || echo ",,,,")
        free=$(awk '/MemAvailable/ {print int($2 / 1024)}' /proc/meminfo)
        echo "$((SECONDS - started)),$stats,$free,$database" >>"$OUT/samples.csv"
        if ((free < MIN_FREE_MB)) && [[ -n $K6_PID ]]; then
            echo "stopped k6: only $free MB of memory left" >>"$OUT/k6.log"
            kill "$K6_PID" 2>/dev/null || true
        fi
        sleep 7
    done
}

invariants() {
    query -F '|' -c "
    select * from (values
      ('a seat with two live tickets', (select count(*) from (select seat_id from tickets
          where seat_id is not null and status <> 'CANCELLED' group by seat_id having count(*) > 1) x)),
      ('a paid order whose tickets do not match its lines', (select count(*) from orders o where o.status = 'PAID' and
          (select coalesce(sum(quantity), 0) from order_items where order_id = o.id)
          <> (select count(*) from tickets where order_id = o.id))),
      ('a ticket on an order that is not paid', (select count(*) from tickets t join orders o on o.id = t.order_id
          where o.status <> 'PAID')),
      ('a standing zone whose counter does not match orders and auctions', (select count(*) from ticket_zones z
          where z.zone_type = 'GENERAL_ADMISSION' and z.capacity - z.available_quantity <>
            (select coalesce(sum(i.quantity), 0) from order_items i join orders o on o.id = i.order_id
              where i.zone_id = z.id and o.source = 'BOOKING' and o.status <> 'CANCELLED')
            + (select count(*) from auctions a where a.zone_id = z.id and a.seat_id is null
              and a.status in ('SCHEDULED', 'LIVE', 'AWAITING_PAYMENT', 'SETTLED')))),
      ('a sold seat without a ticket', (select count(*) from seats s where s.status = 'SOLD' and not exists
          (select 1 from tickets t where t.seat_id = s.id and t.status <> 'CANCELLED'))),
      ('a ticket on a seat that is not sold', (select count(*) from tickets t join seats s on s.id = t.seat_id
          where t.status <> 'CANCELLED' and s.status <> 'SOLD')),
      ('a held seat without an unpaid order', (select count(*) from seats s where s.status = 'HELD' and not exists
          (select 1 from orders o where o.id = s.held_by_order_id and o.status = 'PENDING_PAYMENT'))),
      ('a paid order without exactly one successful payment', (select count(*) from orders o where o.status = 'PAID'
          and (select count(*) from payments p where p.order_id = o.id and p.status = 'SUCCEEDED') <> 1)),
      ('a successful payment on an order that is not paid', (select count(*) from payments p join orders o
          on o.id = p.order_id where p.status = 'SUCCEEDED' and o.status <> 'PAID')),
      ('an auction price that is not its highest bid', (select count(*) from auctions a where a.current_price
          is distinct from (select max(amount) from bids b where b.auction_id = a.id))),
      ('an auction whose bid count is wrong', (select count(*) from auctions a where a.bid_count
          <> (select count(*) from bids b where b.auction_id = a.id))),
      ('a bid below the one before plus the increment', (select count(*) from (select auction_id,
          amount - lag(amount) over (partition by auction_id order by id) as step from bids) b
          join auctions a on a.id = b.auction_id where b.step < a.min_increment)),
      ('an auction still live 2 minutes after its end', (select count(*) from auctions
          where status = 'LIVE' and ends_at < now() - interval '2 minutes')),
      ('an unpaid order 12 minutes past its deadline', (select count(*) from orders
          where status = 'PENDING_PAYMENT' and expires_at < now() - interval '12 minutes')),
      ('a stored payment callback that was not processed', (select count(*) from payment_webhook_events
          where processed_at is null))
    ) as c(what, found)"
}

write_report() {
    local summary=$OUT/summary.json
    out "# Sale day, $(date '+%Y-%m-%d %H:%M')"
    out ""
    out "- Commit $(git rev-parse --short HEAD); $(awk -F': ' '/model name/ {print $2; exit}' /proc/cpuinfo), $(nproc) threads"
    out "- app ${APP_CPUS:-2} CPU / 2 GB, PostgreSQL ${DB_CPUS:-2} CPU / 1 GB, Redis 1 CPU, pool ${POOL:-10}; k6 on the same machine"
    out "- $MINUTES minutes; shoppers $BASE, peak $PEAK when the sale opens at minute $(awk "BEGIN {printf \"%.1f\", $MINUTES / 10}"); $BIDDERS bidders"

    out ""
    out "## Minute by minute"
    out ""
    out "CPU: 200% = both cores busy. Memory and orders are the last sample of the minute."
    out ""
    out "| minute | req/s | p95 ms | failed | app CPU | app MB | db CPU | lock waits (max) | orders paid | pending | cancelled |"
    out "|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|"
    local minute last=$(((MINUTES * 60 - 1) / 60))
    for minute in $(seq 0 $((last + 1))); do
        local http system
        http=$(jq -r --arg m "$minute" '
            .metrics["http_req_duration{minute:\($m)}"].values as $d
            | .metrics["http_req_failed{minute:\($m)}"].values as $f
            | select(($d.count // 0) > 0)
            | "\($d.count / 60 | round) | \($d["p(95)"] | round) | \($f.rate * 10000 | round / 100)%"' "$summary")
        [[ -n $http ]] || continue
        system=$(awk -F, -v m="$minute" 'NR > 1 && int($1 / 60) == m {n++; ac += $2; am = $3; dc += $4;
                if ($9 + 0 > locks) locks = $9; pending = $10; paid = $11; cancelled = $12}
            END {if (n) printf "%.0f%% | %d | %.0f%% | %d | %d | %d | %d", ac / n, am, dc / n, locks, paid, pending, cancelled;
                 else printf "- | - | - | - | - | - | -"}' "$OUT/samples.csv")
        out "| $minute | $http | $system |"
    done

    out ""
    out "## By endpoint"
    out ""
    out "| endpoint | requests | p50 ms | p95 ms | p99 ms | max ms | failed |"
    out "|---|--:|--:|--:|--:|--:|--:|"
    jq -r '.metrics as $m | $m | keys[] | select(startswith("http_req_duration{name:")) as $k
        | ($k | ltrimstr("http_req_duration{name:") | rtrimstr("}")) as $name
        | $m[$k].values as $d | $m["http_req_failed{name:\($name)}"].values as $f
        | select(($d.count // 0) > 0)
        | "| \($name) | \($d.count) | \($d.med | round) | \($d["p(95)"] | round) | \($d["p(99)"] | round) | \($d.max | round) | \($f.rate * 10000 | round / 100)% |"' \
        "$summary" >>"$REPORT"
    out ""
    jq -r '"Total: \(.metrics.http_reqs.values.count) requests, \(.metrics.http_reqs.values.rate | round) req/s on average."' "$summary" >>"$REPORT"

    out ""
    out "## What happened"
    out ""
    out "| | count |"
    out "|---|--:|"
    local name
    for name in bookings payments_ok payments_declined duplicate_ipn_answered_02 abandoned seat_conflicts sold_out \
        not_on_sale bids_accepted bids_outbid bids_too_late auction_orders_paid auction_orders_ignored \
        paid_but_not_applied unexpected; do
        out "| ${name//_/ } | $(jq -r --arg n "$name" '.metrics[$n].values.count // 0' "$summary") |"
    done

    out ""
    out "## Data after the run"
    out ""
    out "| check | found (must be 0) |"
    out "|---|--:|"
    invariants | awk -F'|' '{printf "| %s | %s |\n", $1, $2}' >>"$REPORT"
    out ""
    query -F ' ' -c "
        select 'orders', status, count(*) from orders group by status
        union all select 'payments', status, count(*) from payments group by status
        union all select 'auctions', status, count(*) from auctions group by status
        union all select 'auction offers', status, count(*) from auction_offers group by status
        union all select 'bids', 'total', count(*) from bids
        order by 1, 2" | awk '{n = $NF; s = $(NF - 1); $NF = ""; $(NF - 1) = ""; sub(/ +$/, ""); printf "- %s %s: %s\n", $0, s, n}' >>"$REPORT"
}

health_report() {
    compose logs --no-color -t app >"$OUT/app.log" 2>&1 || true
    compose logs --no-color -t database >"$OUT/database.log" 2>&1 || true
    out ""
    out "## Health"
    out ""
    local service
    for service in app database cache; do
        out "- $service: $(docker inspect -f 'status {{.State.Status}}, restarts {{.RestartCount}}, killed for memory {{.State.OOMKilled}}' "$(compose ps -aq "$service")")"
    done
    out "- application log: $(grep -c ' ERROR ' "$OUT/app.log" || true) ERROR and $(grep -c ' WARN ' "$OUT/app.log" || true) WARN lines"
    out "- database log: $(grep -c 'deadlock detected' "$OUT/database.log" || true) deadlocks, $(grep -c 'still waiting for' "$OUT/database.log" || true) lock waits over 1 s, $(grep -c 'duration: ' "$OUT/database.log" || true) statements over 1 s"
    out "- garbage collection: $(grep -E 'GC\([0-9]+\) Pause' "$OUT/app.log" | grep -oE '[0-9]+\.[0-9]+ms$' | awk '{sub("ms", ""); n++; sum += $1; if ($1 > max) max = $1} END {printf "%d pauses, %.1f s in total, longest %.0f ms", n, sum / 1000, max}')"
    out ""
    out "Most frequent WARN and ERROR messages:"
    out ""
    out '```'
    { grep -E ' (WARN|ERROR) ' "$OUT/app.log" || true; } \
        | sed -E 's/^.* (WARN|ERROR) [0-9]+ --- \[[^]]*\] \[[^]]*\] /\1 /; s/[0-9a-f]{32}/<ref>/g; s/[0-9]+/N/g' \
        | cut -c1-220 | sort | uniq -c | sort -rn | head -8 >>"$REPORT"
    out '```'
}

for tool in docker k6 openssl jq curl basenc; do
    command -v "$tool" >/dev/null || die "$tool is not installed"
done
((PEAK + BIDDERS <= USERS)) || die "PEAK + BIDDERS must not exceed USERS ($USERS): every simulated person has an account"

mkdir -p "$OUT"
say "building the application image"
compose build app >"$OUT/build.log" 2>&1 || { tail -40 "$OUT/build.log" >&2; die "the image did not build"; }
trap cleanup EXIT

say "fresh stack"
compose down -v --remove-orphans >/dev/null 2>&1
# only the keys are used here: people log in through the API
loadtest/tokens.sh target/loadtest 1
compose up -d >/dev/null 2>&1 || { compose logs --tail 50 >&2; die "the stack did not start"; }
ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(compose ps -q app)")
BASE_URL=http://$ip:8080
for attempt in $(seq 120); do
    if curl -sf -o /dev/null --max-time 2 "$BASE_URL/v3/api-docs"; then break; fi
    if [[ $attempt == 120 ]]; then die "the application did not start"; fi
    sleep 1
done
query -q -v users="$USERS" -v open_in="$((MINUTES * 60 / 10))" -f - <loadtest/seed-realistic.sql >/dev/null

say "sale day: $MINUTES minutes"
k6 run --quiet -e BASE_URL="$BASE_URL" -e SUMMARY="$PWD/$OUT/summary.json" -e MINUTES="$MINUTES" -e BASE="$BASE" \
    -e PEAK="$PEAK" -e BIDDERS="$BIDDERS" loadtest/realistic.js >"$OUT/k6.log" 2>&1 &
K6_PID=$!
sample &
SAMPLER_PID=$!
wait "$K6_PID" || true
K6_PID=
kill "$SAMPLER_PID" 2>/dev/null || true
SAMPLER_PID=
[[ -s $OUT/summary.json ]] || { tail -30 "$OUT/k6.log" >&2; die "k6 produced no result"; }

say "letting the background jobs finish for ${SETTLE}s"
sleep "$SETTLE"
write_report
health_report
say "report: $REPORT"
cat "$REPORT"
