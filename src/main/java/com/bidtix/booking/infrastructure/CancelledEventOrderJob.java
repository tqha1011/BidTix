package com.bidtix.booking.infrastructure;

import com.bidtix.config.metrics.Metrics.Job;
import com.bidtix.config.metrics.Metrics;
import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderItem;
import com.bidtix.booking.infrastructure.OrderStockReleaser.Released;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.payment.service.RefundRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Cleans up the orders of CANCELLED events: unpaid booking orders are cancelled and give their stock back; paid orders
 * (booking and auction) are cancelled with their tickets and their money is to be refunded. Unpaid auction orders are not
 * ours: the auction module cancels them.
 * <p>
 * The job looks for state, not for a message: an order of a cancelled event that is still PENDING_PAYMENT or PAID. So
 * whatever slips in around the cancellation (a booking in flight, a late payment callback) is picked up by a later run.
 * <p>
 * Unpaid orders are handled like {@link OrderCancellationJob} handles expired ones: a batch of ids per transaction
 * (lock with {@code SKIP LOCKED}, cancel with the row count checked, stock back), one order per transaction if the
 * batch fails, gate flags cleared after the commit. They get no grace period: a payment confirmed afterwards finds the
 * order cancelled and is marked for refund.
 * <p>
 * Paid orders are money, so each one has its own transaction: lock it, order CANCELLED, its tickets CANCELLED, its
 * payment REFUND_PENDING, every row count checked. All four happen or none does, and an order that fails is logged and
 * retried on the next run without touching the others. The provider is not called here: {@code RefundPaymentJob} does
 * that. Their stock is not given back, because nobody can buy for a cancelled event.
 * <p>
 * An event is marked ({@code orders_cleaned_at}), and not looked at again, once it has no unpaid or paid order left, its
 * auctions are cleaned up (until then the closing job can still create an order) and it was cancelled more than
 * {@link #SETTLE_WINDOW} ago, so that a request already running at the cancellation has committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelledEventOrderJob {
    static final int BATCH_SIZE = 100;
    static final String REFUND_REASON = "The event was cancelled";
    static final Duration SETTLE_WINDOW = Duration.ofMinutes(5);

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final OrderStockReleaser stockReleaser;
    private final RefundRequestService refundRequests;
    private final EventRepository eventRepository;
    private final Metrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 5_000)
    public void cancelOrdersOfCancelledEvents() {
        cancelUnpaidOrders();
        refundPaidOrders();
        markCleanedEvents();
    }

    private void cancelUnpaidOrders() {
        int cancelled = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = orderRepository.findOrderPendingPaymentOfCancelledEvent(BATCH_SIZE, lastId);
            if (!batch.isEmpty()) {
                cancelled += cancelBatch(batch);
                lastId = batch.getLast();   // the next batch starts after it, so a failing order is not read again
            }
        } while (batch.size() == BATCH_SIZE);

        if (cancelled > 0) {
            log.info("Cancelled {} unpaid orders of cancelled events", cancelled);
        }
    }

    /** The whole batch in one transaction; if that fails, each order in its own, so one does not hold back the others. */
    private int cancelBatch(List<Long> orderIds) {
        try {
            return cancelInTransaction(orderIds);
        } catch (RuntimeException e) {
            log.warn("Could not cancel a batch of {} orders of cancelled events, trying them one by one: {}",
                    orderIds.size(), e.toString());
        }

        int cancelled = 0;
        for (Long orderId : orderIds) {
            try {
                cancelled += cancelInTransaction(List.of(orderId));
            } catch (RuntimeException e) {
                metrics.jobItemFailed(Job.CANCELLED_EVENT_ORDERS);
                log.error("Could not cancel order {} of a cancelled event; it is retried on the next run", orderId, e);
            }
        }
        return cancelled;
    }

    private int cancelInTransaction(List<Long> orderIds) {
        Released released = Objects.requireNonNull(transactionTemplate.execute(status -> cancel(orderIds)));
        stockReleaser.clearGate(released);
        return released.orders();
    }

    /** One transaction. Orders that are not ours to cancel (locked elsewhere, paid, already cancelled) are left out. */
    private Released cancel(List<Long> orderIds) {
        List<Long> ids = orderRepository.lockPendingBookingOrders(orderIds);
        if (ids.isEmpty()) {
            return Released.NONE;
        }
        if (orderRepository.markOrderCancelled(ids, clock.instant()) != ids.size()) {
            // The rows are locked by us, so this cannot happen unless the lock and the UPDATE disagree. Roll back.
            throw new IllegalStateException("Locked " + ids.size() + " orders but could not cancel all of them");
        }
        return stockReleaser.release(ids);
    }

    private void refundPaidOrders() {
        int refunded = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = orderRepository.findOrderNeedsToRefund(BATCH_SIZE, lastId);
            for (Long orderId : batch) {
                try {
                    if (Boolean.TRUE.equals(transactionTemplate.execute(status -> refund(orderId)))) {
                        refunded++;
                    }
                } catch (RuntimeException e) {
                    metrics.jobItemFailed(Job.CANCELLED_EVENT_ORDERS);
                    log.error("Could not refund order {} of a cancelled event; it is retried on the next run", orderId, e);
                }
            }
            if (!batch.isEmpty()) {
                lastId = batch.getLast();
            }
        } while (batch.size() == BATCH_SIZE);

        if (refunded > 0) {
            log.info("Cancelled {} paid orders of cancelled events and asked for their refunds", refunded);
        }
    }

    /**
     * One order, one transaction. False when it is not ours to refund: another job holds it, or it is not PAID any more.
     * Anything unexpected throws, which rolls the whole order back.
     */
    private boolean refund(long orderId) {
        Order order = orderRepository.lockPaidOrder(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        // one ticket per unit; reading the items here, before the updates, loads them
        int tickets = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        Instant now = clock.instant();

        if (orderRepository.markPaidOrderCancelled(orderId, now) != 1) {
            throw new IllegalStateException("Locked order " + orderId + " but could not cancel it");
        }
        int cancelledTickets = ticketRepository.cancelValidTickets(orderId, now);
        if (cancelledTickets != tickets) {
            // e.g. a ticket already used at the door: a person has to look at it
            throw new IllegalStateException("Order " + orderId + " has " + tickets + " tickets, but only "
                    + cancelledTickets + " could be cancelled");
        }
        if (!refundRequests.requestRefund(orderId, REFUND_REASON)) {
            throw new IllegalStateException("Order " + orderId + " is paid but has no successful payment to refund");
        }
        return true;
    }

    private void markCleanedEvents() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = clock.instant();
            List<Long> done = orderRepository.findCancelledEventsWithoutOpenOrders(now.minus(SETTLE_WINDOW));
            if (!done.isEmpty()) {
                eventRepository.markOrdersCleaned(done, now);
            }
        });
    }
}
