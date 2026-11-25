package com.bidtix.event.controller;

import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An organizer's own events, drafts included. */
class MyEventsApiTest extends ApiTest {

    @Test
    void allMyEventsAreListed_newestFirst_whateverTheirStatus() throws Exception {
        UUID me = user("ORGANIZER");
        UUID published = Catalog.event(jdbc, me, "PUBLISHED");
        UUID draft = Catalog.event(jdbc, me, "DRAFT");
        Catalog.event(jdbc, user("ORGANIZER"), "DRAFT");

        mvc.perform(get("/api/v1/events/mine").with(as(me, "ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].publicId").value(draft.toString()))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[1].publicId").value(published.toString()))
                .andExpect(jsonPath("$.items[1].venuePublicId").exists());
        mvc.perform(get("/api/v1/events/mine?size=1&page=1").with(as(me, "ORGANIZER")))
                .andExpect(jsonPath("$.items[0].publicId").value(published.toString()));
    }

    @Test
    void onlyOrganizersHaveEvents() throws Exception {
        mvc.perform(get("/api/v1/events/mine").with(as(user("CUSTOMER"), "CUSTOMER"))).andExpect(status().isForbidden());
    }
}
