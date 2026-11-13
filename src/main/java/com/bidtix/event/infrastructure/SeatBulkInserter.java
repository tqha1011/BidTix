package com.bidtix.event.infrastructure;

import com.bidtix.event.dtos.CreateZoneRequest.SeatRowRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inserts many seats with one JDBC batch. {@code seats.id} is an IDENTITY column, which stops Hibernate from
 * batching inserts, so {@code saveAll} would send one statement per seat. Public ids are still generated in Java.
 * <p>
 * It joins the caller's transaction (JpaTransactionManager shares its connection with JdbcTemplate), so
 * the zone and its seats are committed or rolled back together. The zone must be flushed before this runs.
 */
@Component
@RequiredArgsConstructor
public class SeatBulkInserter {

    private static final String INSERT =
            "insert into seats (zone_id, row_label, seat_number, public_id) values (?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    /** @return the number of seats inserted */
    public int insert(long zoneId, List<SeatRowRequest> rows) {
        List<Object[]> batch = new ArrayList<>();
        for (SeatRowRequest row : rows) {
            String label = row.rowLabel().trim();
            for (int number = row.fromSeat(); number <= row.toSeat(); number++) {
                batch.add(new Object[]{zoneId, label, number, UUID.randomUUID()});
            }
        }
        jdbc.batchUpdate(INSERT, batch);
        return batch.size();
    }
}
