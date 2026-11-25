package com.bidtix.event.controller;

import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One event with its zones: what a customer needs to know before booking. */
class EventDetailApiTest extends ApiTest {

    @Test
    void aPublishedEvent_showsItsZonesWithWhatIsStillAvailable() throws Exception {
        UUID organizer = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, organizer, "PUBLISHED");
        UUID standing = Catalog.standingZone(jdbc, event, 100, 40);
        UUID seated = Catalog.seatedZone(jdbc, event, 3);
        jdbc.update("update seats set status = 'SOLD' where public_id = ?", Catalog.seats(jdbc, seated).get(0));

        mvc.perform(get("/api/v1/events/{id}", event).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.publicId").value(event.toString()))
                .andExpect(jsonPath("$.event.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.zones.length()").value(2))
                .andExpect(jsonPath("$.zones[0].publicId").value(standing.toString()))
                .andExpect(jsonPath("$.zones[0].zoneType").value("GENERAL_ADMISSION"))
                .andExpect(jsonPath("$.zones[0].price").value(300000))
                .andExpect(jsonPath("$.zones[0].capacity").value(100))
                .andExpect(jsonPath("$.zones[0].available").value(40))
                .andExpect(jsonPath("$.zones[1].publicId").value(seated.toString()))
                .andExpect(jsonPath("$.zones[1].available").value(2));
    }

    @Test
    void aZoneWithNoSeatLeft_showsZero() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        Catalog.seatedZone(jdbc, event, 1);
        jdbc.update("update seats set status = 'SOLD' where zone_id in (select z.id from ticket_zones z "
                + "join events e on e.id = z.event_id where e.public_id = ?)", event);

        mvc.perform(get("/api/v1/events/{id}", event).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(jsonPath("$.zones[0].available").value(0));
    }

    @Test
    void aDraft_isSeenByItsOrganizerOnly() throws Exception {
        UUID owner = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, owner, "DRAFT");

        mvc.perform(get("/api/v1/events/{id}", event).with(as(owner, "ORGANIZER"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/events/{id}", event).with(as(user("ORGANIZER"), "ORGANIZER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOTFOUND"));
        mvc.perform(get("/api/v1/events/{id}", event).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownEvent_isNotFound_andALoginIsNeeded() throws Exception {
        mvc.perform(get("/api/v1/events/{id}", UUID.randomUUID()).with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/events/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
