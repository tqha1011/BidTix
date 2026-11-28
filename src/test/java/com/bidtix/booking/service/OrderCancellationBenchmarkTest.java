package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.support.PostgresIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What it costs to cancel unpaid orders in one transaction per order, per 10, per 100 or per 500
 * ({@code OrderCancellationJob} does one per order). Two experiments, both on the real application and PostgreSQL:
 * <ol>
 *   <li><b>The job alone</b>: how long it takes to cancel N expired orders (seated: 2 seats each; standing: 1-2 tickets
 *       each, all from one zone counter) at each transaction size.</li>
 *   <li><b>The job next to customers</b>: while the job cancels N standing orders, {@code bookers} threads keep buying
 *       tickets of the same zone through {@code BookingService.bookTicket}. Their latency before and during the job tells
 *       what a long transaction costs the people who are buying: they all queue on the zone counter row that every
 *       refund updates.</li>
 * </ol>
 * The strategies are written here, from the same repository calls the job uses; only the number of orders per
 * transaction changes. They skip the Redis gate clearing (the same for every size, and not a database cost).
 * The orders are seeded with a deadline one hour away and cancelled with a clock two hours ahead, so the scheduled
 * job of the application never takes them.
 * <p>
 * Run it with {@code make bench-cancel}. Knobs: {@code -Dbench.runs=3 -Dbench.scale=1 -Dbench.sizes=1,10,100,500
 * -Dbench.bookers=16}. Writes {@code target/benchmark/order-cancellation.md}.
 */
@Tag("benchmark")
class OrderCancellationBenchmarkTest extends PostgresIntegrationTest {

    private static final int USERS = 50;
    private static final int FREE_STOCK = 1_000_000;   // what the customers can buy in experiment 2

    @Autowired OrderRepository orders;
    @Autowired SeatRepository seats;
    @Autowired TicketZoneRepository zones;
    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private record Seeded(Fixture fixture, long eventId, long gaZoneId, long seatedZoneId, int orders, int capacity) {
    }

    private record Alone(String scenario, int perTransaction, int transactions, double seconds, double ordersPerSecond) {
    }

    private record Booking(long startNanos, long nanos, boolean ok) {
    }

    private record Window(int count, double perSecond, double p50, double p95, double p99, double max) {
    }

    private record Contended(int perTransaction, double jobSeconds, Window before, Window during, int errors) {
    }

    @Test
    void compareTransactionSizes() throws Exception {
        int runs = Integer.getInteger("bench.runs", 3);
        int scale = Integer.getInteger("bench.scale", 1);
        int bookers = Integer.getInteger("bench.bookers", 16);
        int[] sizes = Arrays.stream(System.getProperty("bench.sizes", "1,10,100,500").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int aloneOrders = 5_000 * scale;
        int contendedOrders = 3_000 * scale;

        // warm-up, discarded: class loading, JIT, connection pool, statement cache
        cancelAlone("standing", 100, aloneOrders / 5);
        cancelAlone("seated", 100, aloneOrders / 5);

        List<Alone> alone = new ArrayList<>();
        for (String scenario : List.of("seated", "standing")) {
            for (int size : sizes) {
                List<Alone> measured = new ArrayList<>();
                for (int i = 0; i < runs; i++) {
                    measured.add(cancelAlone(scenario, size, aloneOrders));
                }
                measured.sort(Comparator.comparingDouble(Alone::seconds));
                alone.add(measured.get(measured.size() / 2));
            }
        }

        contend(100, contendedOrders / 3, bookers);   // warm-up, discarded
        List<Contended> contended = new ArrayList<>();
        for (int size : sizes) {
            List<Contended> measured = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                measured.add(contend(size, contendedOrders, bookers));
            }
            measured.sort(Comparator.comparingDouble(c -> c.during().p99()));
            contended.add(measured.get(measured.size() / 2));
        }

        String report = report(alone, contended, runs, aloneOrders, contendedOrders, bookers);
        System.out.println(report);
        Path out = Path.of("target", "benchmark", "order-cancellation.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        assertThat(contended).allSatisfy(c -> assertThat(c.errors()).as("booking errors").isZero());
    }

    // ---- the strategy: orders per transaction ------------------------------------------------------------------

    /** Same steps as {@code OrderCancellationJob}, but {@code perTransaction} orders share one transaction. */
    private int cancelAll(int perTransaction, Instant now) {
        int readSize = Math.max(perTransaction, 100);
        long lastId = 0;
        int cancelled = 0;
        List<Long> ids;
        do {
            ids = orders.findExpiredPendingOrderIds(now, lastId, readSize);
            for (int from = 0; from < ids.size(); from += perTransaction) {
                List<Long> group = ids.subList(from, Math.min(from + perTransaction, ids.size()));
                cancelled += tx.execute(status -> cancelGroup(group, now));
            }
            if (!ids.isEmpty()) {
                lastId = ids.get(ids.size() - 1);
            }
        } while (ids.size() == readSize);
        return cancelled;
    }

    private int cancelGroup(List<Long> group, Instant now) {
        List<Long> locked = group.stream().filter(id -> orders.lockExpiredPendingOrder(id, now).isPresent()).toList();
        if (locked.isEmpty()) {
            return 0;
        }
        if (orders.markOrderCancelled(locked, now) != locked.size()) {
            throw new IllegalStateException("could not cancel every locked order");
        }
        seats.findHeldByOrderIds(locked);
        seats.releaseSeatsHeldByOrders(locked);
        var standing = orders.sumStandingQuantityByZone(locked);
        for (var zone : standing) {
            if (zones.refundQuantity(zone.getZoneId(), zone.getQuantity().intValue()) != 1) {
                throw new IllegalStateException("zone not found");
            }
        }
        zones.findAllById(standing.stream().map(OrderRepository.ZoneQuantity::getZoneId).toList());
        return locked.size();
    }

    // ---- experiment 1: the job alone ---------------------------------------------------------------------------

    private Alone cancelAlone(String scenario, int perTransaction, int orderCount) {
        boolean seated = scenario.equals("seated");
        Seeded seeded = seed(seated, orderCount, 0);

        long start = System.nanoTime();
        int cancelled = cancelAll(perTransaction, Instant.now().plus(2, ChronoUnit.HOURS));
        double seconds = (System.nanoTime() - start) / 1e9;

        assertThat(cancelled).as("orders cancelled").isEqualTo(orderCount);
        assertThat(count("select count(*) from orders where event_id = ? and status = 'CANCELLED'", seeded.eventId()))
                .isEqualTo(orderCount);
        if (seated) {
            assertThat(count("select count(*) from seats where zone_id = ? and status = 'HELD'", seeded.seatedZoneId()))
                    .as("seats still held").isZero();
        } else {
            assertThat(available(seeded)).as("every ticket came back").isEqualTo(seeded.capacity());
        }
        int transactions = (int) Math.ceil(orderCount / (double) perTransaction);
        return new Alone(scenario, perTransaction, transactions, seconds, orderCount / seconds);
    }

    // ---- experiment 2: the job next to customers --------------------------------------------------------------

    private Contended contend(int perTransaction, int orderCount, int bookers) throws Exception {
        Seeded seeded = seed(false, orderCount, FREE_STOCK);
        Fixture f = seeded.fixture();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(bookers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(bookers);
        List<Future<List<Booking>>> workers = new ArrayList<>();
        for (int t = 0; t < bookers; t++) {
            UUID user = f.userIds().get(t % USERS);
            workers.add(pool.submit(() -> {
                List<Booking> log = new ArrayList<>();
                ready.countDown();
                go.await();
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    boolean ok = true;
                    try {
                        bookingService.bookTicket(new BookingTicketRequest(f.eventId(), f.zoneId(), null, 1), user, UUID.randomUUID().toString());
                    } catch (RuntimeException e) {
                        ok = false;
                    }
                    log.add(new Booking(t0, System.nanoTime() - t0, ok));
                }
                return log;
            }));
        }
        ready.await();
        long begin = System.nanoTime();
        go.countDown();

        Thread.sleep(2_500);   // customers alone: the baseline
        long jobStart = System.nanoTime();
        int cancelled = cancelAll(perTransaction, Instant.now().plus(2, ChronoUnit.HOURS));
        long jobEnd = System.nanoTime();
        Thread.sleep(300);
        stop.set(true);

        List<Booking> all = new ArrayList<>();
        for (Future<List<Booking>> worker : workers) {
            all.addAll(worker.get());
        }
        pool.shutdown();

        assertThat(cancelled).isEqualTo(orderCount);
        int sold = (int) all.stream().filter(Booking::ok).count();
        assertThat(count("select count(*) from tickets where zone_id = ?", seeded.gaZoneId())).as("tickets issued").isEqualTo(sold);
        assertThat(available(seeded)).as("stock: capacity - sold, every cancelled order's tickets are back")
                .isEqualTo(seeded.capacity() - sold);

        long baselineFrom = begin + 500_000_000L;   // skip the first half second: pool and caches still warming
        Window before = window(all, baselineFrom, jobStart);
        Window during = window(all, jobStart, jobEnd);
        int errors = (int) all.stream().filter(b -> !b.ok()).count();
        return new Contended(perTransaction, (jobEnd - jobStart) / 1e9, before, during, errors);
    }

    private static Window window(List<Booking> all, long fromNanos, long toNanos) {
        long[] nanos = all.stream().filter(b -> b.startNanos() >= fromNanos && b.startNanos() < toNanos)
                .mapToLong(Booking::nanos).sorted().toArray();
        double seconds = (toNanos - fromNanos) / 1e9;
        if (nanos.length == 0) {
            return new Window(0, 0, 0, 0, 0, 0);
        }
        return new Window(nanos.length, nanos.length / seconds, percentile(nanos, 0.50), percentile(nanos, 0.95),
                percentile(nanos, 0.99), nanos[nanos.length - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)] / 1e6;
    }

    // ---- data ---------------------------------------------------------------------------------------------------

    /**
     * Seeds {@code orderCount} unpaid orders whose deadline is an hour away. Seated: each holds 2 seats of the seated
     * zone. Standing: each has an item of 1 or 2 tickets in the standing zone, whose counter is lowered by the total.
     * {@code freeStock} more tickets stay on sale in the standing zone.
     */
    private Seeded seed(boolean seated, int orderCount, int freeStock) {
        int capacity = 2 * orderCount + freeStock;
        Fixture f = GeneralAdmissionFixtures.create(em, tx, capacity, USERS);
        long userId = jdbc.queryForObject("select id from users where public_id = ?", Long.class, f.userIds().get(0));
        long eventId = jdbc.queryForObject("select id from events where public_id = ?", Long.class, f.eventId());
        long gaZoneId = f.zoneDbId();
        long seatedZoneId = jdbc.queryForObject("select id from ticket_zones where public_id = ?", Long.class, f.seatedZoneId());
        Timestamp deadline = Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS));

        jdbc.update("insert into orders (user_id, event_id, source, total_amount, expires_at, order_code) "
                        + "select ?, ?, 'BOOKING', 0, ?, 'ORDER_' || upper(substr(md5(gen_random_uuid()::text), 1, 14)) "
                        + "from generate_series(1, ?)",
                userId, eventId, deadline, orderCount);

        if (seated) {
            jdbc.update("insert into seats (zone_id, row_label, seat_number) select ?, 'B', g from generate_series(1, ?) g",
                    seatedZoneId, 2 * orderCount);
            jdbc.update("insert into order_items (order_id, zone_id, seat_id, quantity, unit_price) "
                            + "select o.id, ?, s.id, 1, 500000 "
                            + "from (select id, row_number() over (order by id) rn from orders where event_id = ?) o "
                            + "join (select id, row_number() over (order by id) rn from seats "
                            + "      where zone_id = ? and row_label = 'B') s on s.rn in (2 * o.rn - 1, 2 * o.rn)",
                    seatedZoneId, eventId, seatedZoneId);
            jdbc.update("update seats set status = 'HELD', held_by_order_id = oi.order_id, hold_expires_at = ? "
                            + "from order_items oi where oi.seat_id = seats.id and seats.zone_id = ?",
                    deadline, seatedZoneId);
        } else {
            jdbc.update("insert into order_items (order_id, zone_id, seat_id, quantity, unit_price) "
                            + "select id, ?, null, 1 + (row_number() over (order by id) % 2)::int, 300000 "
                            + "from orders where event_id = ?",
                    gaZoneId, eventId);
            jdbc.update("update ticket_zones set available_quantity = available_quantity - "
                            + "(select sum(quantity) from order_items where zone_id = ?) where id = ?",
                    gaZoneId, gaZoneId);
        }
        return new Seeded(f, eventId, gaZoneId, seatedZoneId, orderCount, capacity);
    }

    private int available(Seeded seeded) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, seeded.gaZoneId());
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    // ---- report -------------------------------------------------------------------------------------------------

    private String report(List<Alone> alone, List<Contended> contended, int runs, int aloneOrders, int contendedOrders,
                          int bookers) {
        int pool = dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : -1;
        StringBuilder sb = new StringBuilder();
        sb.append("### Cancelling unpaid orders: one transaction per order or per many\n\n");
        sb.append("- Full application, PostgreSQL ").append(jdbc.queryForObject("select version()", String.class).split(",")[0])
                .append(" in Docker, connection pool ").append(pool).append(", cores ")
                .append(Runtime.getRuntime().availableProcessors()).append(", JVM ").append(System.getProperty("java.version"))
                .append("\n- Per cell: 1 warm-up run (discarded) + ").append(runs)
                .append(" measured runs on fresh data; the row is the median run\n\n");

        sb.append("**1. The job alone** (").append(String.format(Locale.ROOT, "%,d", aloneOrders)).append(" expired orders)\n\n");
        sb.append("| orders | per transaction | transactions | seconds | orders/s |\n|---|--:|--:|--:|--:|\n");
        for (Alone a : alone) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %,d | %.2f | %,.0f |%n",
                    a.scenario(), a.perTransaction(), a.transactions(), a.seconds(), a.ordersPerSecond()));
        }

        sb.append("\n**2. The job next to customers** (").append(String.format(Locale.ROOT, "%,d", contendedOrders))
                .append(" expired standing orders in the zone; ").append(bookers)
                .append(" threads keep buying 1 ticket of that zone; latency of `bookTicket`)\n\n");
        sb.append("| per transaction | job s | before: req/s | p50 ms | p99 ms | max ms | during: req/s | p50 ms | p99 ms | max ms | errors |\n");
        sb.append("|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Contended c : contended) {
            sb.append(String.format(Locale.ROOT, "| %d | %.2f | %,.0f | %.1f | %.1f | %.1f | %,.0f | %.1f | %.1f | %.1f | %d |%n",
                    c.perTransaction(), c.jobSeconds(),
                    c.before().perSecond(), c.before().p50(), c.before().p99(), c.before().max(),
                    c.during().perSecond(), c.during().p50(), c.during().p99(), c.during().max(), c.errors()));
        }
        return sb.toString();
    }
}
