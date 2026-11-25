package com.bidtix.event.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.event.service.EventService;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changing and removing the zones of a draft event. */
class ManageZoneApiTest extends ApiTest {

    private static final String ZONE = "/api/v1/events/{event}/zones/{zone}";

    @Autowired BookingService bookingService;
    @Autowired EventService eventService;
    @Autowired com.bidtix.event.service.ZoneService zoneService;

    @Test
    void aStandingZoneOfADraft_changesItsNamePriceAndCapacity() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID zone = Catalog.standingZone(jdbc, event, 100, 100);

        change(me, event, zone, "{\"name\":\" Floor \",\"price\":450000,\"capacity\":80}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Floor"))
                .andExpect(jsonPath("$.price").value(450000))
                .andExpect(jsonPath("$.capacity").value(80));

        assertThat(row(zone)).containsEntry("capacity", 80).containsEntry("available_quantity", 80);
    }

    @Test
    void aStandingZoneCannotShrinkBelowWhatIsTaken() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 6);   // 4 taken

        change(me, event, zone, "{\"name\":\"Floor\",\"price\":1000,\"capacity\":3}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        change(me, event, zone, "{\"name\":\"Floor\",\"price\":1000,\"capacity\":5}").andExpect(status().isOk());

        assertThat(row(zone)).containsEntry("capacity", 5).containsEntry("available_quantity", 1);
    }

    @Test
    void aSeatedZoneKeepsItsSeats_onlyNameAndPriceChange() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID zone = Catalog.seatedZone(jdbc, event, 3);

        change(me, event, zone, "{\"name\":\"VIP\",\"price\":900000}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.seatCount").value(3));
        change(me, event, zone, "{\"name\":\"VIP\",\"price\":900000,\"capacity\":4}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoZonesOfAnEventCannotShareAName() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID first = Catalog.standingZone(jdbc, event, 10, 10);
        UUID second = Catalog.standingZone(jdbc, event, 10, 10);
        String taken = jdbc.queryForObject("select name from ticket_zones where public_id = ?", String.class, first);

        change(me, event, second, "{\"name\":\"" + taken + "\",\"price\":1000}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ZONE_NAME_TAKEN"));
    }

    @Test
    void aZoneOfADraft_isRemovedWithItsSeats() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID zone = Catalog.seatedZone(jdbc, event, 2);
        UUID seat = Catalog.seats(jdbc, zone).get(0);

        remove(me, event, zone).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from ticket_zones where public_id = ?", Integer.class, zone)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from seats where public_id = ?", Integer.class, seat)).isZero();
    }

    @Test
    void aZoneSomebodyOrderedFrom_staysWhole() throws Exception {
        UUID me = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, me, "DRAFT");
        UUID zone = Catalog.seatedZone(jdbc, event, 2);
        bookingService.bookTicket(new BookingTicketRequest(event, zone, List.of(Catalog.seats(jdbc, zone).get(0)), null),
                user("CUSTOMER"));

        remove(me, event, zone).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        assertThat(Catalog.seats(jdbc, zone)).hasSize(2);
    }

    @Test
    void zonesOfAPublishedEvent_areFixed_andOnlyTheOwnerTouchesADraft() throws Exception {
        UUID me = user("ORGANIZER");
        UUID published = Catalog.event(jdbc, me, "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, published, 10, 10);
        change(me, published, zone, "{\"name\":\"X\",\"price\":1}").andExpect(status().isConflict());
        remove(me, published, zone).andExpect(status().isConflict());

        UUID draft = Catalog.event(jdbc, me, "DRAFT");
        UUID draftZone = Catalog.standingZone(jdbc, draft, 10, 10);
        remove(user("ORGANIZER"), draft, draftZone).andExpect(status().isForbidden());
        remove(me, draft, zone).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ZONE_NOTFOUND"));
    }

    @Test
    void publishingWhileTheLastZoneIsRemoved_neverPublishesAnEventWithoutZones() throws Exception {
        UUID me = user("ORGANIZER");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                UUID event = Catalog.event(jdbc, me, "DRAFT");
                UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (Runnable call : List.<Runnable>of(() -> eventService.publish(me, event),
                        () -> zoneService.deleteZone(me, event, zone))) {
                    calls.add(pool.submit(() -> {
                        go.await();
                        call.run();
                        return null;
                    }));
                }
                go.countDown();
                int refused = 0;
                for (Future<?> call : calls) {
                    try {
                        call.get(30, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        refused++;
                    }
                }

                Map<String, Object> state = jdbc.queryForMap("select e.status, count(z.id) as zones from events e "
                        + "left join ticket_zones z on z.event_id = e.id where e.public_id = ? group by e.status", event);
                assertThat(refused).as("exactly one of the two wins").isEqualTo(1);
                assertThat(state.get("status").equals("PUBLISHED")).isEqualTo(((Long) state.get("zones")) == 1L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions change(UUID organizer, UUID event, UUID zone, String body) throws Exception {
        return mvc.perform(put(ZONE, event, zone).with(as(organizer, "ORGANIZER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions remove(UUID organizer, UUID event, UUID zone) throws Exception {
        return mvc.perform(delete(ZONE, event, zone).with(as(organizer, "ORGANIZER")));
    }

    private Map<String, Object> row(UUID zone) {
        return jdbc.queryForMap("select capacity, available_quantity from ticket_zones where public_id = ?", zone);
    }
}
