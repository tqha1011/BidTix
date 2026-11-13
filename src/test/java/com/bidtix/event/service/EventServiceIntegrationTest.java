package com.bidtix.event.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.support.PostgresIntegrationTest;
import com.bidtix.user.domain.User;
import com.bidtix.user.domain.UserRole;
import com.bidtix.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired VenueService venueService;
    @Autowired EventService eventService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private final Instant now = Instant.now();

    @Test
    void createVenue_returnsItsPublicId() {
        VenueResponse venue = venueService.create(new CreateVenueRequest(" Arena ", "1 Street", "Hanoi"));

        assertThat(venue.publicId()).isNotNull();
        assertThat(venue.name()).isEqualTo("Arena");
        assertThat(jdbc.queryForObject("select count(*) from venues where public_id = ?", Integer.class,
                venue.publicId())).isEqualTo(1);
    }

    @Test
    void createEvent_savesADraftOwnedByTheCaller_withDefaultMaxTickets() {
        User organizer = organizer();
        VenueResponse venue = venueService.create(new CreateVenueRequest("Arena", "1 Street", "Hanoi"));

        EventResponse event = eventService.createEvent(organizer.getPublicId(), request(venue.publicId(), 30, 31, 1, 29, null));

        assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.venuePublicId()).isEqualTo(venue.publicId());
        assertThat(event.maxTicketsPerUser()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select organizer_id from events where public_id = ?", Long.class,
                event.publicId())).isEqualTo(organizer.getId());
    }

    @Test
    void createEvent_unknownVenue_isVenueNotFound() {
        User organizer = organizer();

        assertThatThrownBy(() -> eventService.createEvent(organizer.getPublicId(),
                request(UUID.randomUUID(), 30, 31, 1, 29, 4)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VENUE_NOTFOUND));
    }

    @Test
    void createEvent_badTimes_areRejectedWithInvalidRequest() {
        User organizer = organizer();
        VenueResponse venue = venueService.create(new CreateVenueRequest("Arena", "1 Street", "Hanoi"));

        // show ends before it starts / sales end before they start / sales end after the show starts
        for (CreateEventRequest bad : new CreateEventRequest[]{
                request(venue.publicId(), 30, 29, 1, 29, 4),
                request(venue.publicId(), 30, 31, 10, 5, 4),
                request(venue.publicId(), 30, 31, 1, 31, 4)}) {
            assertThatThrownBy(() -> eventService.createEvent(organizer.getPublicId(), bad))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    private User organizer() {
        return userRepository.save(new User("organizer-" + UUID.randomUUID() + "@test.dev", "hash", "Org",
                UserRole.ORGANIZER));
    }

    /** All offsets are days from now. */
    private CreateEventRequest request(UUID venue, int starts, int ends, int saleStarts, int saleEnds, Integer max) {
        return new CreateEventRequest(venue, "Concert", null,
                now.plus(starts, ChronoUnit.DAYS), now.plus(ends, ChronoUnit.DAYS),
                now.plus(saleStarts, ChronoUnit.DAYS), now.plus(saleEnds, ChronoUnit.DAYS), max);
    }
}
