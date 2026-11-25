package com.bidtix.booking.infrastructure;

import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.SeatRepository.HeldSeat;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.payment.service.ConfirmationGrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * After each commit the booking gate flags of the zones and seats that got stock back are cleared (see
 * {@link BookingGateway}); clearing before the commit would let a request set the flag again while the stock
 * is still taken.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCancellationJob {
    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;
    private final TicketZoneRepository zoneRepository;
    private final BookingGateway bookingGateway;
    private final TransactionTemplate transactionTemplate;
    private final ConfirmationGrace confirmationGrace;
    private final Clock clock;

    /** What a transaction gave back: how many orders, and which gate flags are now out of date. */
    private record Released(int orders, Map<UUID, List<UUID>> seatsByZone, List<UUID> standingZones) {
        static final Released NONE = new Released(0, Map.of(), List.of());
    }

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
                log.error("Could not cancel expired order {}; it is retried on the next run", orderId, e);
            }
        }
        return cancelled;
    }

    private int cancelInTransaction(List<Long> orderIds) {
        Released released = Objects.requireNonNull(transactionTemplate.execute(status -> cancel(orderIds)));
        clearGate(released);
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

        // Read before the update: afterwards the seats no longer say which order held them.
        List<HeldSeat> heldSeats = seatRepository.findHeldByOrderIds(ids);
        int released = seatRepository.releaseSeatsHeldByOrders(ids);
        if (released != heldSeats.size()) {
            log.warn("Expected to release {} seats, but actually released {}", heldSeats.size(), released);
        }

        // ascending zone id (the query orders it), so two jobs never lock the counters in opposite order
        var standing = orderRepository.sumStandingQuantityByZone(ids);
        for (var zone : standing) {
            if (zoneRepository.refundQuantity(zone.getZoneId(), zone.getQuantity().intValue()) != 1) {
                throw new IllegalStateException("Zone " + zone.getZoneId() + " not found to give tickets back to");
            }
        }
        List<UUID> standingZones = zoneRepository.findAllById(standing.stream().map(OrderRepository.ZoneQuantity::getZoneId).toList())
                .stream().map(TicketZone::getPublicId).toList();

        Map<UUID, List<UUID>> seatsByZone = heldSeats.stream().collect(Collectors.groupingBy(
                HeldSeat::getZonePublicId, Collectors.mapping(HeldSeat::getSeatPublicId, Collectors.toList())));
        return new Released(ids.size(), seatsByZone, standingZones);
    }

    /** Only after the commit: the stock is really back, so the "sold out" memory is wrong from now on. */
    private void clearGate(Released released) {
        released.standingZones().forEach(bookingGateway::clearQuantityRejected);
        released.seatsByZone().forEach(bookingGateway::clearSeatFlags);
    }
}
