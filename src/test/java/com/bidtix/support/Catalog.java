package com.bidtix.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Venues, events, zones and seats inserted by SQL, for tests that read or change them through the API. */
public final class Catalog {

    private Catalog() {
    }

    public static UUID venue(JdbcTemplate jdbc) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("insert into venues (public_id, name, address, city) values (?, ?, '1 Street', 'Hanoi')",
                publicId, "Arena " + publicId);
        return publicId;
    }

    /** An event in this status, 30 days ahead, on sale now, owned by this organizer. */
    public static UUID event(JdbcTemplate jdbc, UUID organizer, String status) {
        UUID publicId = UUID.randomUUID();
        UUID venue = venue(jdbc);
        jdbc.update("insert into events (public_id, organizer_id, venue_id, title, starts_at, ends_at, sale_starts_at, "
                        + "sale_ends_at, status) select ?, u.id, v.id, 'Concert', now() + interval '30 days', "
                        + "now() + interval '31 days', now() - interval '1 day', now() + interval '29 days', ? "
                        + "from users u, venues v where u.public_id = ? and v.public_id = ?",
                publicId, status, organizer, venue);
        return publicId;
    }

    public static UUID standingZone(JdbcTemplate jdbc, UUID event, int capacity, int available) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("insert into ticket_zones (public_id, event_id, name, zone_type, price, capacity, available_quantity) "
                        + "select ?, id, ?, 'GENERAL_ADMISSION', 300000, ?, ? from events where public_id = ?",
                publicId, "Standing " + publicId, capacity, available, event);
        return publicId;
    }

    /** A SEATED zone with row A, seats 1..count, all available. */
    public static UUID seatedZone(JdbcTemplate jdbc, UUID event, int count) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("insert into ticket_zones (public_id, event_id, name, zone_type, price, capacity) "
                + "select ?, id, ?, 'SEATED', 500000, ? from events where public_id = ?",
                publicId, "Seated " + publicId, count, event);
        for (int number = 1; number <= count; number++) {
            jdbc.update("insert into seats (zone_id, row_label, seat_number) select id, 'A', ? from ticket_zones "
                    + "where public_id = ?", number, publicId);
        }
        return publicId;
    }

    /** The zone's seats, by seat number. */
    public static List<UUID> seats(JdbcTemplate jdbc, UUID zone) {
        return new ArrayList<>(jdbc.queryForList("select s.public_id from seats s join ticket_zones z on z.id = s.zone_id "
                + "where z.public_id = ? order by s.row_label, s.seat_number", UUID.class, zone));
    }
}
