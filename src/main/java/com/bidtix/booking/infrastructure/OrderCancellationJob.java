package com.bidtix.booking.infrastructure;

import com.bidtix.config.metrics.Metrics.Job;
import com.bidtix.config.metrics.Metrics;
import com.bidtix.booking.infrastructure.OrderStockReleaser.Released;
import com.bidtix.payment.service.ConfirmationGrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cancels booking orders that were not paid in time and gives their stock back (seats HELD -> AVAILABLE, standing
 * quantity added back to the zone counter). Auction orders are not touched: the auction owns its stock. An order with a
 * payment still waiting for the provider's callback is kept for a grace period after its deadline (see
 * {@link ConfirmationGrace}).
 * <p>
 * The job reads a batch of up to {@value #BATCH_SIZE} expired order ids and handles the whole batch in one
 * transaction: lock the orders ({@code FOR UPDATE SKIP LOCKED}, lowest id first), cancel them with one conditional
 * UPDATE (row count checked) and return their stock, so an order is never cancelled without its stock coming back.
 * Several app instances can run this at once: an order locked by another job, or paid meanwhile, is skipped.
 * If a batch fails it rolls back as a whole and its orders are retried one per transaction, so a single order that
 * cannot be cancelled never blocks the others.
 * One transaction per batch, not per order, because it is 20-30x faster and the stock comes back sooner (see the
 * "Cancelling unpaid orders" section of docs/benchmarks.md).
 * <p>
 * The stock is given back by {@link OrderStockReleaser}, which also clears the booking gate flags after each commit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationJob {
    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderStockReleaser stockReleaser;
    private final Metrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final ConfirmationGrace confirmationGrace;
    private final Clock clock;

    // fixedDelay: the next run starts one minute after the previous one ended, so runs of one instance never overlap
    @Scheduled(fixedDelay = 60_000)
    public void cancelExpiredOrders() {
        int cancelled = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = orderRepository.findExpiredPendingOrderIds(clock.instant(), lastId, BATCH_SIZE);
            if (!batch.isEmpty()) {
                cancelled += cancelBatch(batch);
                lastId = batch.getLast();   // the next batch starts after it, so a failing order is not read again
            }
        } while (batch.size() == BATCH_SIZE);

        if (cancelled > 0) {
            log.info("Cancelled {} unpaid orders", cancelled);
        }
    }

    /**
     * The whole batch in one transaction. If it fails it rolls back as a whole, so nothing is half done; then each
     * order gets its own transaction, so one order that cannot be cancelled does not hold back the others.
     */
    private int cancelBatch(List<Long> orderIds) {
        try {
            return cancelInTransaction(orderIds);
        } catch (RuntimeException e) {
            log.warn("Could not cancel a batch of {} expired orders, trying them one by one: {}", orderIds.size(), e.toString());
        }

        int cancelled = 0;
        for (Long orderId : orderIds) {
            try {
                cancelled += cancelInTransaction(List.of(orderId));
            } catch (RuntimeException e) {
                metrics.jobItemFailed(Job.ORDER_CANCELLATION);
                log.error("Could not cancel expired order {}; it is retried on the next run", orderId, e);
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
        Instant now = clock.instant();
        // an order with a payment still unconfirmed is only taken once its grace period is over too
        Set<Long> awaiting = confirmationGrace.ordersAwaitingConfirmation(orderIds);
        Instant graceOver = confirmationGrace.cutoff(now);
        // the ids come lowest first, so every job locks rows in the same order
        List<Long> ids = orderIds.stream()
                .filter(id -> orderRepository.lockExpiredPendingOrder(id, awaiting.contains(id) ? graceOver : now)
                        .isPresent())
                .toList();
        if (ids.isEmpty()) {
            return Released.NONE;
        }

        if (orderRepository.markOrderCancelled(ids, now) != ids.size()) {
            // The rows are locked by us, so this cannot happen unless the lock and the UPDATE disagree. Roll back.
            throw new IllegalStateException("Locked " + ids.size() + " orders but could not cancel all of them");
        }

        return stockReleaser.release(ids);
    }
}
