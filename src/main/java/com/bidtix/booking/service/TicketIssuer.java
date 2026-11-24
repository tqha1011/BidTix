package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderItem;
import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.infrastructure.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Issues the tickets of an order that has just been marked PAID. Has no transaction of its own: it runs inside the one
 * that marks the order paid, so the order and its tickets commit together.
 */
@Component
@RequiredArgsConstructor
public class TicketIssuer {

    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;

    /** An order with seats ({@link #issueForSeats}) or with a quantity of standing tickets ({@link #issueForQuantity}). */
    public List<Ticket> issue(Order order) {
        boolean seated = order.getItems().stream().anyMatch(item -> item.getSeatId() != null);
        return seated ? issueForSeats(order) : issueForQuantity(order);
    }

    /**
     * Seats HELD -> SOLD, then one ticket per seat. The seats must still be held by this order; the row count proves it, and
     * a seat whose hold was lost makes this fail ({@code ORDER_NOT_PAYABLE}) instead of selling it twice.
     */
    public List<Ticket> issueForSeats(Order order) {
        List<OrderItem> items = order.getItems();
        long seatCount = items.stream().filter(item -> item.getSeatId() != null).count();
        if (seatRepository.markSeatsSoldByOrderId(order.getId()) != seatCount) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "Seat hold is no longer valid");
        }
        var tickets = items.stream()
                .map(item -> new Ticket(item, order.getEventId(), order.getUserId()))
                .toList();
        ticketRepository.saveAllAndFlush(tickets);
        return tickets;
    }

    /**
     * Nothing to mark SOLD: the quantity was already subtracted when the order was reserved. One ticket per unit, all
     * pointing at the same order item (each person at the door needs their own QR code).
     */
    public List<Ticket> issueForQuantity(Order order) {
        var tickets = order.getItems().stream()
                .flatMap(item -> IntStream.range(0, item.getQuantity())
                        .mapToObj(i -> new Ticket(item, order.getEventId(), order.getUserId())))
                .toList();
        ticketRepository.saveAllAndFlush(tickets);
        return tickets;
    }
}
