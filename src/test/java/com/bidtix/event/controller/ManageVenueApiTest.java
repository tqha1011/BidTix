package com.bidtix.event.controller;

import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Listing, changing and removing venues. */
class ManageVenueApiTest extends ApiTest {

    private static final String BODY = "{\"name\":\" New Hall \",\"address\":\"2 Road\",\"city\":\"Hue\"}";

    @Test
    void venuesAreListedByName_forAnyLoggedInUser() throws Exception {
        UUID venue = Catalog.venue(jdbc);
        // sorts before every other venue in the shared database
        jdbc.update("update venues set name = ? where public_id = ?", "  !! first " + venue, venue);

        mvc.perform(get("/api/v1/venues?size=1").with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].publicId").exists())
                .andExpect(jsonPath("$.items[0].name").exists())
                .andExpect(jsonPath("$.totalElements").isNumber());
        mvc.perform(get("/api/v1/venues")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminChangesAVenue_andTheCachedCopyGoes() throws Exception {
        UUID venue = Catalog.venue(jdbc);
        UUID admin = user("ADMIN");
        mvc.perform(get("/api/v1/venues/{id}", venue).with(as(admin, "ADMIN"))).andExpect(status().isOk());   // cached

        mvc.perform(put("/api/v1/venues/{id}", venue).with(as(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New Hall"))
                .andExpect(jsonPath("$.city").value("Hue"));

        Awaitility.await().untilAsserted(() -> mvc.perform(get("/api/v1/venues/{id}", venue).with(as(admin, "ADMIN")))
                .andExpect(jsonPath("$.name").value("New Hall")));
    }

    @Test
    void anUnusedVenueCanBeRemoved_andIsGoneFromTheCacheToo() throws Exception {
        UUID venue = Catalog.venue(jdbc);
        UUID admin = user("ADMIN");
        mvc.perform(get("/api/v1/venues/{id}", venue).with(as(admin, "ADMIN"))).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/venues/{id}", venue).with(as(admin, "ADMIN"))).andExpect(status().isNoContent());

        Awaitility.await().untilAsserted(() -> mvc.perform(get("/api/v1/venues/{id}", venue).with(as(admin, "ADMIN")))
                .andExpect(status().isNotFound()));
    }

    @Test
    void aVenueWithEvents_cannotBeRemoved() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "DRAFT");
        UUID venue = jdbc.queryForObject("select v.public_id from venues v join events e on e.venue_id = v.id "
                + "where e.public_id = ?", UUID.class, event);

        mvc.perform(delete("/api/v1/venues/{id}", venue).with(as(user("ADMIN"), "ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENUE_IN_USE"));

        assertThat(jdbc.queryForObject("select count(*) from venues where public_id = ?", Integer.class, venue)).isEqualTo(1);
    }

    @Test
    void onlyAnAdminChangesVenues() throws Exception {
        UUID venue = Catalog.venue(jdbc);
        UUID organizer = user("ORGANIZER");

        mvc.perform(put("/api/v1/venues/{id}", venue).with(as(organizer, "ORGANIZER"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/venues/{id}", venue).with(as(organizer, "ORGANIZER"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/venues/{id}", UUID.randomUUID()).with(as(user("ADMIN"), "ADMIN")))
                .andExpect(status().isNotFound());
    }
}
