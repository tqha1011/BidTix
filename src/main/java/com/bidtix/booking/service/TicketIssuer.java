package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderItem;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.infrastructure.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

    /**
     * Issues the tickets of an order, whatever kind it is: standing tickets ({@link #issueForQuantity}), seats of a booking
     * ({@link #issueForSeats}) or the one reserved seat of an auction order ({@link #issueForAuctionSeat}).
     *
     * @param now when the order was paid
     */
    public List<Ticket> issue(Order order, Instant now) {
        boolean seated = order.getItems().stream().anyMatch(item -> item.getSeatId() != null);
        if (!seated) {
            return issueForQuantity(order);
        }
        return order.getSource() == OrderSource.AUCTION ? issueForAuctionSeat(order, now) : issueForSeats(order);
    }

    /**
     * An auction order sells one seat that was taken out of sale when the auction was created (RESERVED_FOR_AUCTION, held by
     * no order), so it is not "held by this order" like a booking's seat. RESERVED_FOR_AUCTION -> SOLD, with the row count
     * checked: a seat that is not reserved any more makes this fail ({@code ORDER_NOT_PAYABLE}) instead of selling it twice.
     */
    public List<Ticket> issueForAuctionSeat(Order order, Instant now) {
        Long seatId = order.getItems().stream().map(OrderItem::getSeatId).filter(Objects::nonNull).findFirst().orElseThrow();
        if (seatRepository.updateAuctionSeatSold(seatId, now) != 1) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "The auctioned seat is no longer reserved for this order");
        }
        var tickets = order.getItems().stream()
                .map(item -> new Ticket(item, order.getEventId(), order.getUserId()))
                .toList();
        ticketRepository.saveAllAndFlush(tickets);
        return tickets;
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
