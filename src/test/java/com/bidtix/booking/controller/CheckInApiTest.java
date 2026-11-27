package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import com.bidtix.support.OrderPayments;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checking tickets in at the gate: a VALID ticket of this event is admitted exactly once, from 2 hours before the start
 * until the end, by the event's organizer. ApiTest turns rate limiting off.
 */
class CheckInApiTest extends ApiTest {

    @Autowired BookingService bookingService;
    @Autowired PayableOrders payableOrders;

    /** An event whose gate is open (it starts in an hour), with the paid tickets of one order. */
    private record Gate(UUID organizer, UUID event, List<UUID> tickets) {
        UUID ticket() {
            return tickets.getFirst();
        }
    }

    // ---- admitting -------------------------------------------------------------------------------

    @Test
    void aValidStandingTicket_isAdmitted_andMarkedUsed() throws Exception {
        Gate gate = standingGate(1);

        scan(gate, gate.ticket()).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketPublicId").value(gate.ticket().toString()))
                .andExpect(jsonPath("$.eventTitle").value("Concert"))
                .andExpect(jsonPath("$.zoneName").isNotEmpty())
                .andExpect(jsonPath("$.rowLabel").doesNotExist())
                .andExpect(jsonPath("$.seatNumber").doesNotExist())
                .andExpect(jsonPath("$.ownerName").value("Test CUSTOMER"))
                .andExpect(jsonPath("$.orderCode").isNotEmpty())
                .andExpect(jsonPath("$.usedAt").isNotEmpty());

        assertThat(statusOf(gate.ticket())).isEqualTo("USED");
        assertThat(jdbc.queryForObject("select used_at is not null from tickets where public_id = ?", Boolean.class,
                gate.ticket())).isTrue();
    }

    @Test
    void aValidSeatedTicket_isAdmitted_withItsSeat() throws Exception {
        Gate gate = seatedGate();

        scan(gate, gate.ticket()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rowLabel").value("A"))
                .andExpect(jsonPath("$.seatNumber").value(1));
    }

    @Test
    void onlyTheScannedTicketOfAnOrderIsUsed() throws Exception {
        Gate gate = standingGate(3);

        scan(gate, gate.tickets().get(1)).andExpect(status().isOk());

        assertThat(gate.tickets()).extracting(this::statusOf).containsExactly("VALID", "USED", "VALID");
    }

    // ---- refusing a ticket -------------------------------------------------------------------------

    @Test
    void aSecondScan_isRefused_andSaysWhenTheTicketWasUsed() throws Exception {
        Gate gate = standingGate(1);
        String first = scan(gate, gate.ticket()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String usedAt = JsonPath.read(first, "$.usedAt");

        scan(gate, gate.ticket()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_USED"))
                .andExpect(jsonPath("$.usedAt").value(usedAt));
    }

    @Test
    void aCancelledTicket_isRefused() throws Exception {
        Gate gate = standingGate(1);
        jdbc.update("update tickets set status = 'CANCELLED' where public_id = ?", gate.ticket());

        scan(gate, gate.ticket()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_CANCELLED"));

        assertThat(statusOf(gate.ticket())).isEqualTo("CANCELLED");
    }

    @Test
    void anUnknownTicket_isNotFound() throws Exception {
        Gate gate = standingGate(1);

        scan(gate, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void aTicketOfAnotherEvent_isNotFound_whateverItsState_andNothingAboutItIsTold() throws Exception {
        Gate other = standingGate(3);
        UUID valid = other.tickets().get(0);
        UUID used = other.tickets().get(1);
        UUID cancelled = other.tickets().get(2);
        scan(other, used).andExpect(status().isOk());
        jdbc.update("update tickets set status = 'CANCELLED' where public_id = ?", cancelled);
        Gate mine = standingGate(1);

        for (UUID ticket : List.of(valid, used, cancelled)) {
            scan(mine, ticket).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                    .andExpect(jsonPath("$.usedAt").doesNotExist());
        }

        assertThat(statusOf(valid)).isEqualTo("VALID");
    }

    // ---- when the gate is open ---------------------------------------------------------------------

    @Test
    void theGateOpens2HoursBeforeTheStart() throws Exception {
        Gate gate = standingGate(1);

        move(gate.event(), "2 hours 5 minutes", "5 hours");
        refusedAsNotOpen(gate);

        move(gate.event(), "1 hour 55 minutes", "5 hours");
        scan(gate, gate.ticket()).andExpect(status().isOk());
    }

    @Test
    void theGateStaysOpenWhileTheEventRuns() throws Exception {
        Gate gate = standingGate(1);
        move(gate.event(), "-1 hour", "1 hour");

        scan(gate, gate.ticket()).andExpect(status().isOk());
    }

    @Test
    void theGateIsClosedOnceTheEventHasEnded() throws Exception {
        Gate gate = standingGate(1);
        move(gate.event(), "-3 hours", "-1 minute");

        refusedAsNotOpen(gate);
    }

    @Test
    void anEventThatIsNotPublishedAdmitsNobody() throws Exception {
        Gate gate = standingGate(1);
        for (String state : List.of("DRAFT", "CANCELLED", "COMPLETED")) {
            jdbc.update("update events set status = ? where public_id = ?", state, gate.event());

            refusedAsNotOpen(gate);
        }
    }

    // ---- who may scan ------------------------------------------------------------------------------

    @Test
    void onlyTheEventsOrganizerMayCheckIn() throws Exception {
        Gate gate = standingGate(1);

        scan(user("ORGANIZER"), gate.event(), gate.ticket()).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/events/{event}/check-ins", gate.event()).with(as(user("CUSTOMER"), "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(gate.ticket())))
                .andExpect(status().isForbidden());
        scan(gate.organizer(), UUID.randomUUID(), gate.ticket()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOTFOUND"));

        assertThat(statusOf(gate.ticket())).isEqualTo("VALID");
    }

    @Test
    void aRequestWithoutATicketIsRefused() throws Exception {
        Gate gate = standingGate(1);

        mvc.perform(post("/api/v1/events/{event}/check-ins", gate.event()).with(as(gate.organizer(), "ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---- concurrency -------------------------------------------------------------------------------

    @Test
    void manyScansOfOneTicketAtOnce_admitItExactlyOnce() throws Exception {
        Gate gate = standingGate(1);
        int scanners = 10;
        ExecutorService pool = Executors.newFixedThreadPool(scanners);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> scans = new ArrayList<>();
        for (int i = 0; i < scanners; i++) {
            scans.add(pool.submit(() -> {
                start.await();
                return scan(gate, gate.ticket()).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> scan : scans) {
            statuses.add(scan.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(200);
        assertThat(statuses).filteredOn(code -> code == 409).hasSize(scanners - 1);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private Gate standingGate(int quantity) {
        UUID organizer = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, organizer, "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
        return gate(organizer, event, new BookingTicketRequest(event, zone, null, quantity));
    }

    private Gate seatedGate() {
        UUID organizer = user("ORGANIZER");
        UUID event = Catalog.event(jdbc, organizer, "PUBLISHED");
        UUID zone = Catalog.seatedZone(jdbc, event, 1);
        return gate(organizer, event, new BookingTicketRequest(event, zone, Catalog.seats(jdbc, zone), null));
    }

    /** Books and pays while the sale is open, then moves the event so that its gate is open now. */
    private Gate gate(UUID organizer, UUID event, BookingTicketRequest request) {
        String code = bookingService.bookTicket(request, user("CUSTOMER")).orderCode();
        OrderPayments.pay(payableOrders, jdbc, code);
        move(event, "1 hour", "4 hours");
        return new Gate(organizer, event, jdbc.queryForList("select t.public_id from tickets t "
                + "join orders o on o.id = t.order_id where o.order_code = ? order by t.id", UUID.class, code));
    }

    /** The event now starts and ends this far from now (negative: in the past); its sale is long over. */
    private void move(UUID event, String startsIn, String endsIn) {
        jdbc.update("update events set sale_starts_at = now() - interval '10 days', "
                + "sale_ends_at = now() - interval '9 days', starts_at = now() + cast(? as interval), "
                + "ends_at = now() + cast(? as interval) where public_id = ?", startsIn, endsIn, event);
    }

    private ResultActions scan(Gate gate, UUID ticket) throws Exception {
        return scan(gate.organizer(), gate.event(), ticket);
    }

    private ResultActions scan(UUID organizer, UUID event, UUID ticket) throws Exception {
        return mvc.perform(post("/api/v1/events/{event}/check-ins", event).with(as(organizer, "ORGANIZER"))
                .contentType(MediaType.APPLICATION_JSON).content(body(ticket)));
    }

    private static String body(UUID ticket) {
        return "{\"ticketPublicId\":\"" + ticket + "\"}";
    }

    private void refusedAsNotOpen(Gate gate) throws Exception {
        scan(gate, gate.ticket()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECK_IN_NOT_OPEN"));
        assertThat(statusOf(gate.ticket())).isEqualTo("VALID");
    }

    private String statusOf(UUID ticket) {
        return jdbc.queryForObject("select status from tickets where public_id = ?", String.class, ticket);
    }
}
