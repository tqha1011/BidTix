package com.bidtix.booking.infrastructure;

import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.SeatRepository.HeldSeat;
import com.bidtix.event.infrastructure.TicketZoneRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cancels orders that were not paid in time and gives their stock back (seats HELD -> AVAILABLE, standing
 * quantity added back to the zone counter).
 * <p>
 * Work is done in batches, one short transaction each: the batch of orders is locked with
 * {@code FOR UPDATE SKIP LOCKED}, cancelled with a conditional UPDATE (row count checked) and its stock is
 * returned in the same transaction, so an order is never cancelled without its stock coming back, or the other
 * way round. Several app instances can run this at once: each takes different orders. An order that is paid
 * meanwhile is not PENDING_PAYMENT any more, so it is neither selected nor changed.
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
    private final Clock clock;

    /** What one batch gave back: how many orders, and which gate flags are now out of date. */
    private record Batch(int orders, Map<UUID, List<UUID>> seatsByZone, List<UUID> standingZones) {
        static final Batch EMPTY = new Batch(0, Map.of(), List.of());
    }

    // fixedDelay: the next run starts one minute after the previous one ended, so runs of one instance never overlap
    @Scheduled(fixedDelay = 60_000)
    public void cancelExpiredOrders() {
        int cancelled = 0;
        Batch batch;
        do {
            try {
                batch = Objects.requireNonNull(transactionTemplate.execute(status -> cancelBatch()));
            } catch (RuntimeException e) {
                // The batch rolled back as a whole, so nothing is half done. Try again on the next run.
                log.error("Cancelling expired orders failed after {} cancelled; will retry on the next run", cancelled, e);
                return;
            }
            clearGate(batch);
            cancelled += batch.orders();
            // Always ask for the first batch again: cancelled orders no longer match, so nothing is skipped.
        } while (batch.orders() == BATCH_SIZE);

        if (cancelled > 0) {
            log.info("Cancelled {} unpaid orders", cancelled);
        }
    }

    private Batch cancelBatch() {
        Instant now = clock.instant();
        List<Long> orderIds = orderRepository.lockExpiredPendingOrderIds(now, BATCH_SIZE);
        if (orderIds.isEmpty()) {
            return Batch.EMPTY;
        }

        int cancelled = orderRepository.markOrderCancelled(orderIds, now);
        if (cancelled != orderIds.size()) {
            // The rows are locked by us, so this cannot happen unless the query and the lock disagree. Roll back.
            throw new IllegalStateException("Locked " + orderIds.size() + " orders but cancelled " + cancelled);
        }

        // Read before the update: afterwards the seats no longer say which order held them.
        List<HeldSeat> heldSeats = seatRepository.findHeldByOrderIds(orderIds);
        int released = seatRepository.releaseSeatsHeldByOrders(orderIds);
        if (released != heldSeats.size()) {
            log.warn("Expected to release {} seats, but actually released {}", heldSeats.size(), released);
        }

        // ascending zone id (the query orders it), so two jobs never lock the counters in opposite order
        var standing = orderRepository.sumStandingQuantityByZone(orderIds);
        for (var zone : standing) {
            if (zoneRepository.refundQuantity(zone.getZoneId(), zone.getQuantity().intValue()) != 1) {
                throw new IllegalStateException("Zone " + zone.getZoneId() + " not found to give tickets back to");
            }
        }
        List<UUID> standingZones = zoneRepository.findAllById(standing.stream().map(OrderRepository.ZoneQuantity::getZoneId).toList())
                .stream().map(TicketZone::getPublicId).toList();

        Map<UUID, List<UUID>> seatsByZone = heldSeats.stream().collect(Collectors.groupingBy(
                HeldSeat::getZonePublicId, Collectors.mapping(HeldSeat::getSeatPublicId, Collectors.toList())));
        return new Batch(orderIds.size(), seatsByZone, standingZones);
    }

    /** Only after the commit: the stock is really back, so the "sold out" memory is wrong from now on. */
    private void clearGate(Batch batch) {
        batch.standingZones().forEach(bookingGateway::clearQuantityRejected);
        batch.seatsByZone().forEach(bookingGateway::clearSeatFlags);
    }
}
