package com.bidtix.auction.service;

import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.CreateZoneRequest.SeatRowRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.VenueService;
import com.bidtix.event.service.ZoneService;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Test data for auctions. Every call creates its own organizer, venue and event: tests never share rows. */
final class AuctionFixtures {

    /**
     * An event owned by {@code organizer} that starts in 30 days, with a SEATED zone (5 seats) and a GENERAL_ADMISSION zone.
     * {@code seatIds} are in seat-number order.
     */
    record Fixture(User organizer, UUID eventId, Instant eventStartsAt, UUID seatedZoneId, List<UUID> seatIds,
                   UUID standingZoneId, long standingZoneDbId) {
    }

    private AuctionFixtures() {
    }

    static Fixture create(UserRepository users, VenueService venues, EventService events, ZoneService zones,
                          JdbcTemplate jdbc, int standingCapacity, boolean publish) {
        Instant now = Instant.now();
        User organizer = users.save(new User("organizer-" + UUID.randomUUID() + "@test.dev", "hash", "Org", UserRole.ORGANIZER));
        UUID venue = venues.create(new CreateVenueRequest("Arena", "1 Street", "Hanoi")).publicId();
        EventResponse event = events.createEvent(organizer.getPublicId(), new CreateEventRequest(venue, "Concert", null,
                now.plus(30, ChronoUnit.DAYS), now.plus(31, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS), null));
        UUID seated = zones.createZone(organizer.getPublicId(), event.publicId(), new CreateZoneRequest(
                "Seated", ZoneType.SEATED, 500_000, null, List.of(new SeatRowRequest("A", 1, 5)))).publicId();
        UUID standing = zones.createZone(organizer.getPublicId(), event.publicId(), new CreateZoneRequest(
                "Standing", ZoneType.GENERAL_ADMISSION, 300_000, standingCapacity, null)).publicId();
        if (publish) {
            events.publish(organizer.getPublicId(), event.publicId());
        }
        List<UUID> seatIds = jdbc.queryForList("select s.public_id from seats s join ticket_zones z on z.id = s.zone_id "
                + "where z.public_id = ? order by s.seat_number", UUID.class, seated);
        long standingDbId = jdbc.queryForObject("select id from ticket_zones where public_id = ?", Long.class, standing);
        return new Fixture(organizer, event.publicId(), event.startsAt(), seated, seatIds, standing, standingDbId);
    }
}
