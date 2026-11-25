package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.payment.service.PayableOrders;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import com.bidtix.support.OrderPayments;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer reading the tickets they own. */
class MyTicketsApiTest extends ApiTest {

    @Autowired BookingService bookingService;
    @Autowired PayableOrders payableOrders;

    @Test
    void ticketsAppearOnceTheOrderIsPaid_withWhatIsNeededAtTheDoor() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.seatedZone(jdbc, event, 2);
        List<UUID> seats = Catalog.seats(jdbc, zone);
        UUID me = user("CUSTOMER");
        String code = bookingService.bookTicket(new BookingTicketRequest(event, zone, seats, null), me).orderCode();

        mvc.perform(get("/api/v1/tickets").with(as(me, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        OrderPayments.pay(payableOrders, jdbc, code);

        mvc.perform(get("/api/v1/tickets").with(as(me, "CUSTOMER")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].ticketPublicId").exists())
                .andExpect(jsonPath("$.items[0].status").value("VALID"))
                .andExpect(jsonPath("$.items[0].eventPublicId").value(event.toString()))
                .andExpect(jsonPath("$.items[0].eventTitle").value("Concert"))
                .andExpect(jsonPath("$.items[0].eventStartsAt").exists())
                .andExpect(jsonPath("$.items[0].zoneName").exists())
                .andExpect(jsonPath("$.items[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.items[0].seatNumber").value(2))   // newest first
                .andExpect(jsonPath("$.items[0].orderCode").value(code));
    }

    @Test
    void aStandingTicketHasNoSeat_andNobodyElseSeesIt() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
        UUID me = user("CUSTOMER");
        String code = bookingService.bookTicket(new BookingTicketRequest(event, zone, null, 1), me).orderCode();
        OrderPayments.pay(payableOrders, jdbc, code);

        mvc.perform(get("/api/v1/tickets").with(as(me, "CUSTOMER")))
                .andExpect(jsonPath("$.items[0].rowLabel").doesNotExist())
                .andExpect(jsonPath("$.items[0].seatNumber").doesNotExist());
        mvc.perform(get("/api/v1/tickets").with(as(user("CUSTOMER"), "CUSTOMER")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/tickets").with(as(user("ORGANIZER"), "ORGANIZER"))).andExpect(status().isForbidden());
    }
}
