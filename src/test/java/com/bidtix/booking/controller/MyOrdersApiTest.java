package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer reading their own orders. */
class MyOrdersApiTest extends ApiTest {

    @Autowired BookingService bookingService;

    @Test
    void myOrdersAreListedNewestFirst_andOnlyMine() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 100, 100);
        UUID me = user("CUSTOMER");
        String first = standing(me, event, zone, 1);
        String second = standing(me, event, zone, 2);
        standing(user("CUSTOMER"), event, zone, 1);

        mvc.perform(get("/api/v1/orders").with(as(me, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].orderCode").value(second))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.items[0].totalAmount").value(600000))
                .andExpect(jsonPath("$.items[0].eventPublicId").value(event.toString()))
                .andExpect(jsonPath("$.items[0].eventTitle").value("Concert"))
                .andExpect(jsonPath("$.items[0].expiresAt").exists())
                .andExpect(jsonPath("$.items[1].orderCode").value(first));
        mvc.perform(get("/api/v1/orders?size=1&page=1").with(as(me, "CUSTOMER")))
                .andExpect(jsonPath("$.items[0].orderCode").value(first));
    }

    @Test
    void oneOrderShowsItsItems_withTheSeats() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.seatedZone(jdbc, event, 2);
        List<UUID> seats = Catalog.seats(jdbc, zone);
        UUID me = user("CUSTOMER");
        String code = bookingService.bookTicket(new BookingTicketRequest(event, zone, seats, null), me, UUID.randomUUID().toString()).orderCode();

        mvc.perform(get("/api/v1/orders/{code}", code).with(as(me, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.orderCode").value(code))
                .andExpect(jsonPath("$.order.source").value("BOOKING"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].zonePublicId").value(zone.toString()))
                .andExpect(jsonPath("$.items[0].seatPublicId").exists())
                .andExpect(jsonPath("$.items[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.items[0].unitPrice").value(500000));
    }

    @Test
    void anotherCustomersOrder_looksLikeAnUnknownOne() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
        String theirs = standing(user("CUSTOMER"), event, zone, 1);

        for (String code : List.of(theirs, "ORDER_UNKNOWN")) {
            mvc.perform(get("/api/v1/orders/{code}", code).with(as(user("CUSTOMER"), "CUSTOMER")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOTFOUND"));
        }
    }

    @Test
    void onlyCustomersHaveOrders() throws Exception {
        mvc.perform(get("/api/v1/orders").with(as(user("ORGANIZER"), "ORGANIZER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
    }

    private String standing(UUID customer, UUID event, UUID zone, int quantity) {
        return bookingService.bookTicket(new BookingTicketRequest(event, zone, null, quantity), customer, UUID.randomUUID().toString()).orderCode();
    }
}
