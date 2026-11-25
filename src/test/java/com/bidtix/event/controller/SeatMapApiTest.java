package com.bidtix.event.controller;

import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The seat map of a seated zone: which seats there are and which can still be picked. */
class SeatMapApiTest extends ApiTest {

    private static final String SEATS = "/api/v1/events/{event}/zones/{zone}/seats";

    @Test
    void everySeatIsListed_byRowAndNumber_withItsStatus() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.seatedZone(jdbc, event, 3);
        List<UUID> seats = Catalog.seats(jdbc, zone);
        jdbc.update("update seats set status = 'SOLD' where public_id = ?", seats.get(1));
        jdbc.update("update seats set status = 'RESERVED_FOR_AUCTION' where public_id = ?", seats.get(2));

        mvc.perform(get(SEATS, event, zone).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].publicId").value(seats.get(0).toString()))
                .andExpect(jsonPath("$[0].rowLabel").value("A"))
                .andExpect(jsonPath("$[0].seatNumber").value(1))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[1].status").value("SOLD"))
                .andExpect(jsonPath("$[2].status").value("RESERVED_FOR_AUCTION"))
                .andExpect(jsonPath("$[0].heldByOrderId").doesNotExist());
    }

    @Test
    void aZoneOfAnotherEvent_isNotFound() throws Exception {
        UUID organizer = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, organizer, "PUBLISHED");
        UUID otherZone = Catalog.seatedZone(jdbc, Catalog.event(jdbc, organizer, "PUBLISHED"), 1);

        mvc.perform(get(SEATS, event, otherZone).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ZONE_NOTFOUND"));
    }

    @Test
    void aStandingZone_hasNoSeatMap() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);

        mvc.perform(get(SEATS, event, zone).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theSeatsOfADraft_areSeenByItsOrganizerOnly() throws Exception {
        UUID owner = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, owner, "DRAFT");
        UUID zone = Catalog.seatedZone(jdbc, event, 1);

        mvc.perform(get(SEATS, event, zone).with(as(owner, "ORGANIZER"))).andExpect(status().isOk());
        mvc.perform(get(SEATS, event, zone).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOTFOUND"));
    }
}
