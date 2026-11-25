package com.bidtix.event.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Deleting a draft: only a draft, with its zones and seats, and never one somebody already ordered from. */
class DeleteEventApiTest extends ApiTest {

    @Autowired BookingService bookingService;

    @Test
    void aDraftIsRemovedWithItsZonesAndSeats() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        Catalog.standingZone(jdbc, event, 10, 10);
        UUID seated = Catalog.seatedZone(jdbc, event, 3);
        UUID seat = Catalog.seats(jdbc, seated).get(0);

        remove(me, event).andExpect(status().isNoContent());

        assertThat(count("events", event)).isZero();
        assertThat(count("ticket_zones", seated)).isZero();
        assertThat(count("seats", seat)).isZero();
    }

    @Test
    void onlyADraftCanBeDeleted() throws Exception {
        UUID me = user("ORGANIZER");
        for (String status : new String[]{"PUBLISHED", "CANCELLED", "COMPLETED"}) {
            UUID event = Catalog.event(jdbc, me, status);
            Catalog.standingZone(jdbc, event, 10, 10);

            remove(me, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

            assertThat(count("events", event)).isEqualTo(1);
        }
    }

    @Test
    void aDraftSomebodyOrderedFrom_staysWhole() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");
        UUID seated = Catalog.seatedZone(jdbc, event, 2);
        UUID seat = Catalog.seats(jdbc, seated).get(0);
        bookingService.bookTicket(new BookingTicketRequest(event, seated, java.util.List.of(seat), null), user("CUSTOMER"));
        // booking refuses a draft; this is the database's guard should one ever have orders
        jdbc.update("update events set status = 'DRAFT' where public_id = ?", event);

        remove(me, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        assertThat(count("events", event)).isEqualTo(1);
        assertThat(count("seats", seat)).isEqualTo(1);
    }

    @Test
    void onlyItsOrganizerMayDeleteIt() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "DRAFT");

        remove(user("ORGANIZER"), event).andExpect(status().isForbidden());
        remove(user("ORGANIZER"), UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(count("events", event)).isEqualTo(1);
    }

    private ResultActions remove(UUID organizer, UUID event) throws Exception {
        return mvc.perform(delete("/api/v1/events/{id}", event).with(as(organizer, "ORGANIZER")));
    }

    private int count(String table, UUID publicId) {
        return jdbc.queryForObject("select count(*) from " + table + " where public_id = ?", Integer.class, publicId);
    }
}
