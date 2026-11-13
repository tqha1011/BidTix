package com.bidtix.benchmark;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Judges a run against the database, not against what the strategies said. The checks are pure functions of
 * (what clients were promised, what the database says), so they can be tested without a database; the small
 * readers at the bottom fetch the database side.
 *
 * <ul>
 *   <li><b>oversold</b>: clients were promised stock the database does not record for them. This is the number
 *       that must be 0 for every strategy except the naive baseline.</li>
 *   <li><b>leaked</b>: the database records stock as taken that no client was promised (a rollback or a
 *       compensation that did not happen). Not an oversell, but stock lost for good.</li>
 * </ul>
 */
public final class InvariantChecker {

    public record Verdict(long oversold, long leaked, List<String> notes) {
        public boolean clean() {
            return oversold == 0 && leaked == 0 && notes.isEmpty();
        }
    }

    /** A request that was told HELD, and the seats it claims. */
    public record Claim(long orderId, List<Long> seatIds) {
    }

    private InvariantChecker() {
    }

    /**
     * GENERAL_ADMISSION. {@code promised} = sum of the quantities of all requests answered HELD;
     * {@code availableInDb} = {@code available_quantity} after the run.
     * A lost update shows as promised &gt; capacity - available.
     */
    public static Verdict checkQuantity(int capacity, int availableInDb, long promised) {
        List<String> notes = new ArrayList<>();
        if (availableInDb < 0 || availableInDb > capacity) {
            notes.add("available_quantity " + availableInDb + " is outside 0.." + capacity);
        }
        long takenInDb = capacity - availableInDb;
        return new Verdict(Math.max(promised - takenInDb, 0), Math.max(takenInDb - promised, 0), notes);
    }

    /**
     * SEATED. {@code holderBySeat}: seat id -&gt; order id that holds it in the database (null when free).
     * Every (winner, seat) pair must be held by that very winner; a pair held by somebody else, or by nobody,
     * is a seat sold twice. A held seat that no winner claimed is leaked.
     */
    public static Verdict checkSeats(Map<Long, Long> holderBySeat, List<Claim> winners) {
        long oversold = 0;
        Map<Long, Long> claimed = new HashMap<>();
        for (Claim claim : winners) {
            for (long seatId : claim.seatIds()) {
                claimed.putIfAbsent(seatId, claim.orderId());
                Long holder = holderBySeat.get(seatId);
                if (holder == null || holder != claim.orderId()) {
                    oversold++;
                }
            }
        }
        long leaked = holderBySeat.entrySet().stream()
                .filter(e -> e.getValue() != null && !claimed.containsKey(e.getKey()))
                .count();
        return new Verdict(oversold, leaked, List.of());
    }

    // ---- database side ---------------------------------------------------------------------

    public static int readAvailable(JdbcTemplate jdbc, long zoneId) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where id = ?", Integer.class, zoneId);
    }

    public static Map<Long, Long> readHolders(JdbcTemplate jdbc, long zoneId) {
        Map<Long, Long> holders = new HashMap<>();
        jdbc.query("select id, held_by_order_id from seats where zone_id = ?", rs -> {
            long seatId = rs.getLong(1);
            long holder = rs.getLong(2);
            boolean free = rs.wasNull();   // must come right after reading the nullable column
            holders.put(seatId, free ? null : holder);
        }, zoneId);
        return holders;
    }
}
