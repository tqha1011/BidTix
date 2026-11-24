package com.bidtix.booking.service;

import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.GeneralAdmissionFixtures.Fixture;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * Redis is unreachable: the gate must step aside, so booking works and PostgreSQL still decides.
 * (A separate class because the spy gives it its own application context.)
 */
class BookingGateRedisDownTest extends PostgresIntegrationTest {

    @Autowired BookingService bookingService;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean StringRedisTemplate redis;

    @Test
    void bookingStillWorks_andTheDatabaseStillDecides_whenRedisIsDown() {
        Fixture f = GeneralAdmissionFixtures.create(em, tx, 3, 3);
        doThrow(new RedisConnectionFailureException("redis is down")).when(redis).opsForValue();

        // standing: a purchase goes through...
        assertThat(bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.zoneId(), null, 2), f.userIds().get(0)).status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        // ...and the sold-out answer comes from the database (it carries the hint), without the failed flag write breaking it
        assertThatThrownBy(() -> bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.zoneId(), null, 2), f.userIds().get(1)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ZONE_SOLD_OUT);
                    assertThat(e.getProperties()).containsEntry("availableQuantity", 1);
                });

        // seated: same
        var seat = f.seatedSeatIds().get(0);
        assertThat(bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seat), null), f.userIds().get(0)).status())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThatThrownBy(() -> bookingService.bookTicket(
                new BookingTicketRequest(f.eventId(), f.seatedZoneId(), List.of(seat), null), f.userIds().get(1)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_UNAVAILABLE));
    }
}
