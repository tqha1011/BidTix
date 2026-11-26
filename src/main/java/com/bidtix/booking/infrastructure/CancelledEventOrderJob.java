package com.bidtix.booking.infrastructure;

import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Cleans up the orders of CANCELLED events: unpaid booking orders are cancelled and give their stock back; paid orders
 * (booking and auction) are cancelled with their tickets and their money is to be refunded. Unpaid auction orders are not
 * ours: the auction module cancels them.
 * <p>
 * The job looks for state, not for a message: an order of a cancelled event that is still PENDING_PAYMENT or PAID. So
 * whatever slips in around the cancellation (a booking in flight, a late payment callback) is picked up by a later run.
 * <p>
 * Skeleton: it does nothing yet.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelledEventOrderJob {
    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final SeatRepository seatRepository;
    private final TicketZoneRepository zoneRepository;
    private final BookingGateway bookingGateway;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 5_000)
    public void cancelOrdersOfCancelledEvents() {
        cancelUnpaidOrders();
        refundPaidOrders();
    }

    private void cancelUnpaidOrders() {
        // TODO 1. read a batch of PENDING_PAYMENT BOOKING order ids whose event is CANCELLED (paged by id)
        // TODO 2. one transaction: lock them (FOR UPDATE SKIP LOCKED), CANCELLED with the row count checked, stock back.
        //         No grace period: a late callback finds the order cancelled and records REFUND_PENDING by itself.
        // TODO 3. after the commit: clear the booking gate flags of the stock that came back
    }

    private void refundPaidOrders() {
        // TODO 1. read a batch of PAID order ids (BOOKING and AUCTION) whose event is CANCELLED (paged by id)
        // TODO 2. one transaction per order: lock it, order and tickets CANCELLED, and ask the payment module (a new
        //         service there) to move its payment SUCCEEDED -> REFUND_PENDING; check every row count.
        //         RefundPaymentJob does the rest. Never call VNPAY here.
    }
}
