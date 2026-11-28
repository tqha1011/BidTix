package com.bidtix.booking.service;

import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.support.ApiTest;
import com.bidtix.support.Catalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** When tickets can be bought at all: only for a published event, and only while its sale is open. */
class BookingSaleRulesTest extends ApiTest {

    @Autowired BookingService bookingService;

    @Test
    void aPublishedEventOnSale_canBeBooked() {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);

        bookingService.bookTicket(new BookingTicketRequest(event, zone, null, 2), user("CUSTOMER"), UUID.randomUUID().toString());

        assertThat(available(zone)).isEqualTo(8);
    }

    @Test
    void anEventThatIsNotPublished_looksUnknown_andNothingIsTaken() {
        for (String status : new String[]{"DRAFT", "CANCELLED", "COMPLETED"}) {
            UUID event = Catalog.event(jdbc, user("ORGANIZER"), status);
            UUID zone = Catalog.standingZone(jdbc, event, 10, 10);

            refused(event, zone, ErrorCode.EVENT_NOTFOUND);
        }
    }

    @Test
    void beforeTheSaleOpens_nothingCanBeBought() {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
        jdbc.update("update events set sale_starts_at = now() + interval '1 hour' where public_id = ?", event);

        refused(event, zone, ErrorCode.EVENT_NOT_ON_SALE);
    }

    @Test
    void afterTheSaleCloses_nothingCanBeBought() {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.standingZone(jdbc, event, 10, 10);
        jdbc.update("update events set sale_ends_at = now() - interval '1 second' where public_id = ?", event);

        refused(event, zone, ErrorCode.EVENT_NOT_ON_SALE);
    }

    @Test
    void aSeatOfAClosedSale_staysFree_andTheApiAnswers409() throws Exception {
        UUID event = Catalog.event(jdbc, user("ORGANIZER"), "PUBLISHED");
        UUID zone = Catalog.seatedZone(jdbc, event, 1);
        List<UUID> seats = Catalog.seats(jdbc, zone);
        jdbc.update("update events set sale_ends_at = now() - interval '1 second' where public_id = ?", event);

        mvc.perform(post("/api/v1/orders").with(as(user("CUSTOMER"), "CUSTOMER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventPublicId\":\"" + event + "\",\"zonePublicId\":\"" + zone
                                + "\",\"seatPublicIds\":[\"" + seats.get(0) + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));

        assertThat(jdbc.queryForObject("select status from seats where public_id = ?", String.class, seats.get(0)))
                .isEqualTo("AVAILABLE");
    }

    private void refused(UUID event, UUID zone, ErrorCode code) {
        UUID customer = user("CUSTOMER");
        assertThatThrownBy(() -> bookingService.bookTicket(new BookingTicketRequest(event, zone, null, 1), customer, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
        assertThat(available(zone)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from orders o join users u on u.id = o.user_id "
                + "where u.public_id = ?", Integer.class, customer)).isZero();
    }

    private int available(UUID zone) {
        return jdbc.queryForObject("select available_quantity from ticket_zones where public_id = ?", Integer.class, zone);
    }
}
