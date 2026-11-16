package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.infrastructure.BookingProperties;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cost of the booking gate measured on the real flow ({@code BookingService.bookTicket}: validation, order insert,
 * stock update, mock payment, ticket issue), with the whole application, PostgreSQL and Redis in Docker.
 * Unlike {@code StrategyBenchmarkTest} (one SQL statement per strategy), the connection pool here is the one the
 * application uses, which is where a request refused before the transaction pays off.
 * <p>
 * Run it twice and compare: {@code make bench-gate} does {@code -Dbidtix.booking.gate-enabled=true} and
 * {@code =false}, writing {@code target/benchmark/booking-gate-on.md} and {@code booking-gate-off.md}.
 * Knobs: {@code -Dbench.threads=500 -Dbench.runs=3 -Dbench.scale=1}.
 * <p>
 * Every run checks the invariants (tickets issued = stock taken, orders = successes, one ticket for the hot seat)
 * and counts <b>false rejections</b>: refused requests that would have fitted in the stock left at the end. Nothing
 * returns stock yet, so stock only ever shrinks and that number must be 0; it is what a gate can get wrong.
 */
@Tag("benchmark")
class BookingGateBenchmarkTest extends PostgresIntegrationTest {

    private static final int USERS = 50;

    @Autowired BookingService bookingService;
    @Autowired BookingProperties properties;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    /** @param seated true: everybody wants the one hot seat; false: standing zone of {@code capacity}, 1 or 2 tickets each */
    private record Scenario(String name, boolean seated, int capacity, int requests) {
    }

    private enum Outcome {SOLD, REFUSED_BY_DATABASE, REFUSED_BY_GATE, REFUSED_SEAT, ERROR}

    private record Run(String scenario, double opsPerSecond, double wallSeconds,
                       double p50, double p95, double p99,
                       int sold, int refusedByDatabase, int refusedByGate, int refusedSeat, int errors,
                       int falseRejections, int ordersLeft, boolean invariantsHold, String problem) {
    }

    @Test
    void compareGateOnTheBookingFlow() throws Exception {
        int threads = Integer.getInteger("bench.threads", 500);
        int runs = Integer.getInteger("bench.runs", 3);
        int scale = Integer.getInteger("bench.scale", 1);
        List<Scenario> scenarios = List.of(
                new Scenario("ga-soldout-tail", false, 50, 5_000 * scale),
                new Scenario("ga-plentiful", false, 20_000 * scale, 5_000 * scale),
                new Scenario("seat-hot", true, 1, 3_000 * scale));

        List<Run> medians = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            execute(scenario, threads);   // warm-up, discarded
            List<Run> measured = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                Run run = execute(scenario, threads);
                measured.add(run);
                if (!run.invariantsHold() || run.falseRejections() > 0 || run.errors() > 0) {
                    problems.add(scenario.name() + ": " + run.problem() + " falseRejections=" + run.falseRejections()
                            + " errors=" + run.errors());
                }
            }
            measured.sort(Comparator.comparingDouble(Run::opsPerSecond));
            medians.add(measured.get(measured.size() / 2));
        }

        String report = report(medians, threads, runs, scale);
        System.out.println(report);
        Path out = Path.of("target", "benchmark", "booking-gate-" + (properties.gateEnabled() ? "on" : "off") + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);

        assertThat(problems).as("invariant breaks, false rejections or unexpected errors").isEmpty();
    }

    // ---- one run -----------------------------------------------------------------------------------------------

    private Run execute(Scenario scenario, int threads) throws Exception {
        Fixture f = GeneralAdmissionFixtures.create(em, tx, scenario.capacity(), USERS);
        UUID hotSeat = f.seatedSeatIds().get(0);
        Random random = new Random(42);
        int n = scenario.requests();
        int[] quantity = new int[n];
        for (int i = 0; i < n; i++) {
            quantity[i] = scenario.seated() ? 1 : 1 + random.nextInt(2);
        }

        Outcome[] outcomes = new Outcome[n];
        long[] nanos = new long[n];
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                UUID user = f.userIds().get(index % USERS);
                BookingTicketRequest request = scenario.seated()
                        ? new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(hotSeat), null)
                        : new BookingTicketRequest(f.eventId(), f.zoneId(), null, quantity[index]);
                try {
                    start.await();
                    long t0 = System.nanoTime();
                    try {
                        bookingService.bookTicket(request, user);
                        outcomes[index] = Outcome.SOLD;
                    } catch (BusinessException e) {
                        outcomes[index] = classify(e);
                    } catch (RuntimeException e) {
                        outcomes[index] = Outcome.ERROR;
                    }
                    nanos[index] = System.nanoTime() - t0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        long wallStart = System.nanoTime();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1e9;
        pool.shutdown();

        return check(scenario, f, hotSeat, quantity, outcomes, nanos, wallSeconds);
    }

    private static Outcome classify(BusinessException e) {
        if (e.getErrorCode() == ErrorCode.ZONE_SOLD_OUT) {
            // the database's answer carries the stock left; the gate's does not
            return e.getProperties().containsKey("availableQuantity")
                    ? Outcome.REFUSED_BY_DATABASE : Outcome.REFUSED_BY_GATE;
        }
        if (e.getErrorCode() == ErrorCode.SEAT_UNAVAILABLE) {
            return Outcome.REFUSED_SEAT;
        }
        return Outcome.ERROR;
    }

    private Run check(Scenario scenario, Fixture f, UUID hotSeat, int[] quantity, Outcome[] outcomes, long[] nanos,
                      double wallSeconds) {
        int sold = 0, byDb = 0, byGate = 0, seat = 0, errors = 0, soldTickets = 0;
        for (int i = 0; i < outcomes.length; i++) {
            switch (outcomes[i]) {
                case SOLD -> {
                    sold++;
                    soldTickets += quantity[i];
                }
                case REFUSED_BY_DATABASE -> byDb++;
                case REFUSED_BY_GATE -> byGate++;
                case REFUSED_SEAT -> seat++;
                case ERROR -> errors++;
            }
        }

        int orders = jdbc.queryForObject(
                "select count(*) from orders o join events e on e.id = o.event_id where e.public_id = ?",
                Integer.class, f.eventId());
        String problem = "";
        int falseRejections = 0;
        if (scenario.seated()) {
            int ticketsOnSeat = jdbc.queryForObject(
                    "select count(*) from tickets t join seats s on s.id = t.seat_id where s.public_id = ?",
                    Integer.class, hotSeat);
            if (sold != 1 || ticketsOnSeat != 1) {
                problem = "hot seat: sold=" + sold + " ticketsOnSeat=" + ticketsOnSeat;
            }
        } else {
            int available = jdbc.queryForObject("select available_quantity from ticket_zones where id = ?",
                    Integer.class, f.zoneDbId());
            int ticketsInDb = jdbc.queryForObject("select count(*) from tickets where zone_id = ?",
                    Integer.class, f.zoneDbId());
            if (soldTickets != ticketsInDb || soldTickets != scenario.capacity() - available || available < 0) {
                problem = "standing: promised=" + soldTickets + " ticketsInDb=" + ticketsInDb + " available=" + available;
            }
            // Stock never comes back, so a refused request that fits in what is left at the end was refused wrongly.
            for (int i = 0; i < outcomes.length; i++) {
                boolean refused = outcomes[i] == Outcome.REFUSED_BY_DATABASE || outcomes[i] == Outcome.REFUSED_BY_GATE;
                if (refused && quantity[i] <= available) {
                    falseRejections++;
                }
            }
        }
        if (orders != sold) {
            problem += " orders=" + orders + " but sold=" + sold;
        }

        long[] sorted = Arrays.stream(nanos).sorted().toArray();
        return new Run(scenario.name(), outcomes.length / wallSeconds, wallSeconds,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sold, byDb, byGate, seat, errors, falseRejections, orders, problem.isBlank(), problem.trim());
    }

    private static double percentile(long[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)] / 1e6;
    }

    // ---- report ------------------------------------------------------------------------------------------------

    private String report(List<Run> rows, int threads, int runs, int scale) {
        int pool = dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : -1;
        StringBuilder sb = new StringBuilder();
        sb.append("### Booking flow, gate ").append(properties.gateEnabled() ? "ON" : "OFF").append("\n\n");
        sb.append("- Full application (`BookingService.bookTicket`), PostgreSQL ")
                .append(jdbc.queryForObject("select version()", String.class).split(",")[0])
                .append(" and Redis in Docker, mock payment\n");
        sb.append("- ").append(threads).append(" simulated users on platform threads, connection pool ").append(pool)
                .append(", gate TTL ").append(properties.gateTtlSeconds()).append(" s, cores ")
                .append(Runtime.getRuntime().availableProcessors()).append(", JVM ")
                .append(System.getProperty("java.version")).append(", scale x").append(scale).append("\n");
        sb.append("- Per scenario: 1 warm-up run (discarded) + ").append(runs)
                .append(" measured runs on a fresh zone; the row is the run with the median throughput\n\n");
        sb.append("| scenario | requests | sold | refused by DB | refused by gate | refused (seat) | errors | **false rejections** | ")
                .append("ops/s | p50 ms | p95 ms | p99 ms | run s |\n");
        sb.append("|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Run r : rows) {
            int requests = r.sold() + r.refusedByDatabase() + r.refusedByGate() + r.refusedSeat() + r.errors();
            sb.append(String.format(Locale.ROOT,
                    "| %s | %,d | %,d | %,d | %,d | %,d | %d | **%d** | %,.0f | %.2f | %.2f | %.2f | %.2f |%n",
                    r.scenario(), requests, r.sold(), r.refusedByDatabase(), r.refusedByGate(), r.refusedSeat(),
                    r.errors(), r.falseRejections(), r.opsPerSecond(), r.p50(), r.p95(), r.p99(), r.wallSeconds()));
        }
        return sb.toString();
    }
}
