package com.bidtix.event.controller;

import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import tools.jackson.databind.json.JsonMapper;

/** Changing an event: everything while it is a draft, only its title and description once it is published. */
class UpdateEventApiTest extends ApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aDraftCanBeChangedEntirely() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID venue = Catalog.venue(jdbc);
        Map<String, Object> body = current(event);
        body.put("venuePublicId", venue.toString());
        body.put("title", "  New title  ");
        body.put("description", "About");
        body.put("startsAt", "2030-01-02T10:00:00Z");
        body.put("endsAt", "2030-01-02T12:00:00Z");
        body.put("saleStartsAt", "2029-01-01T00:00:00Z");
        body.put("saleEndsAt", "2030-01-01T00:00:00Z");
        body.put("maxTicketsPerUser", 2);

        update(me, event, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.venuePublicId").value(venue.toString()))
                .andExpect(jsonPath("$.startsAt").value("2030-01-02T10:00:00Z"))
                .andExpect(jsonPath("$.maxTicketsPerUser").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        assertThat(jdbc.queryForObject("select description from events where public_id = ?", String.class, event))
                .isEqualTo("About");
    }

    @Test
    void aPublishedEvent_changesItsTitleAndDescriptionOnly() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");
        Map<String, Object> body = current(event);
        body.put("title", "Renamed");

        update(me, event, body).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Renamed"));

        for (String field : new String[]{"venuePublicId", "startsAt", "saleEndsAt", "maxTicketsPerUser"}) {
            Map<String, Object> changed = current(event);
            changed.put(field, switch (field) {
                case "venuePublicId" -> Catalog.venue(jdbc).toString();
                case "startsAt" -> Instant.parse((String) changed.get("startsAt")).plusSeconds(60).toString();
                case "saleEndsAt" -> Instant.parse((String) changed.get("saleEndsAt")).minusSeconds(60).toString();
                default -> 9;
            });
            update(me, event, changed).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
        }
    }

    @Test
    void aCancelledEvent_cannotBeChanged() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "CANCELLED");

        update(me, event, current(event)).andExpect(status().isConflict());
    }

    @Test
    void theTimeRulesAreTheSameAsWhenCreating() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        Map<String, Object> body = current(event);
        body.put("endsAt", body.get("startsAt"));

        update(me, event, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void onlyItsOrganizerMayChangeIt() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "DRAFT");

        update(user("ORGANIZER"), event, current(event)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/events/{id}", event).with(as(user("CUSTOMER"), "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(current(event))))
                .andExpect(status().isForbidden());
    }

    @Test
    void thePublicListShowsTheNewTitleAtOnce() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "PUBLISHED");
        // far in the past of every other event's start, so it is on the first page, which is cached by this read
        jdbc.update("update events set starts_at = now() + interval '1 minute', ends_at = now() + interval '2 minutes', "
                + "sale_starts_at = now() - interval '2 days', sale_ends_at = now() where public_id = ?", event);
        UUID reader = user("CUSTOMER");
        mvc.perform(get("/api/v1/events").with(as(reader, "CUSTOMER"))).andExpect(status().isOk());
        Map<String, Object> body = current(event);
        String title = "Fresh " + event;
        body.put("title", title);

        update(me, event, body).andExpect(status().isOk());

        org.awaitility.Awaitility.await().untilAsserted(() -> mvc.perform(get("/api/v1/events").with(as(reader, "CUSTOMER")))
                .andExpect(jsonPath("$.items[?(@.publicId == '" + event + "')].title").value(title)));
    }

    private ResultActions update(UUID organizer, UUID event, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/v1/events/{id}", event).with(as(organizer, "ORGANIZER"))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    /** The event as it is now, in the shape of the request. */
    private Map<String, Object> current(UUID event) {
        return new HashMap<>(jdbc.queryForObject("select v.public_id as venue, e.title, e.starts_at, e.ends_at, "
                        + "e.sale_starts_at, e.sale_ends_at, e.max_tickets_per_user from events e join venues v "
                        + "on v.id = e.venue_id where e.public_id = ?",
                (row, n) -> Map.<String, Object>of("venuePublicId", row.getString("venue"), "title", row.getString("title"),
                        "startsAt", row.getTimestamp("starts_at").toInstant().toString(),
                        "endsAt", row.getTimestamp("ends_at").toInstant().toString(),
                        "saleStartsAt", row.getTimestamp("sale_starts_at").toInstant().toString(),
                        "saleEndsAt", row.getTimestamp("sale_ends_at").toInstant().toString(),
                        "maxTicketsPerUser", row.getInt("max_tickets_per_user")), event));
    }
}
