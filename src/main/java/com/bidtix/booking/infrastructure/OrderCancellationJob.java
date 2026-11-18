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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cancels orders that were not paid in time and gives their stock back (seats HELD -> AVAILABLE, standing
 * quantity added back to the zone counter).
 * <p>
 * The job reads a batch of up to {@value #BATCH_SIZE} expired order ids, then handles each order in its own short
 * transaction: lock that one row ({@code FOR UPDATE SKIP LOCKED}), cancel it with a conditional UPDATE (row count
 * checked) and return its stock. An order is never cancelled without its stock coming back, and one order that
 * fails rolls back only itself. Several app instances can run this at once: an order locked by another job, or
 * paid meanwhile, is skipped.
 * <p>
 * After each commit the booking gate flags of the zone and seats that got stock back are cleared (see
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

    /** The gate flags that are out of date after one order gave its stock back. */
    private record Released(Map<UUID, List<UUID>> seatsByZone, List<UUID> standingZones) {
    }

    // fixedDelay: the next run starts one minute after the previous one ended, so runs of one instance never overlap
    @Scheduled(fixedDelay = 60_000)
    public void cancelExpiredOrders() {
        int cancelled = 0;
        int failed = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = orderRepository.findExpiredPendingOrderIds(clock.instant(), lastId, BATCH_SIZE);
            for (Long orderId : batch) {
                try {
                    Released released = transactionTemplate.execute(status -> cancelOrder(orderId));
                    if (released != null) {
                        clearGate(released);
                        cancelled++;
                    }
                } catch (RuntimeException e) {
                    // This order rolled back as a whole, nothing is half done. The others go on; it is retried next run.
                    failed++;
                    log.error("Could not cancel expired order {}", orderId, e);
                }
            }
            if (!batch.isEmpty()) {
                lastId = batch.getLast();   // next batch starts after it, so a failing order is not read again
            }
        } while (batch.size() == BATCH_SIZE);

        if (cancelled > 0 || failed > 0) {
            log.info("Cancelled {} unpaid orders, {} failed", cancelled, failed);
        }
    }

    /** One order, one transaction. Returns null when the order is not ours to cancel (locked elsewhere, paid, already cancelled). */
    private Released cancelOrder(Long orderId) {
        Instant now = clock.instant();
        if (orderRepository.lockExpiredPendingOrder(orderId, now).isEmpty()) {
            return null;
        }

        List<Long> ids = List.of(orderId);
        if (orderRepository.markOrderCancelled(ids, now) != 1) {
            // The row is locked by us, so this cannot happen unless the lock and the UPDATE disagree. Roll back.
            throw new IllegalStateException("Locked order " + orderId + " but could not cancel it");
        }

        // Read before the update: afterwards the seats no longer say which order held them.
        List<HeldSeat> heldSeats = seatRepository.findHeldByOrderIds(ids);
        int released = seatRepository.releaseSeatsHeldByOrders(ids);
        if (released != heldSeats.size()) {
            log.warn("Order {}: expected to release {} seats, but actually released {}", orderId, heldSeats.size(), released);
        }

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
        return new Released(seatsByZone, standingZones);
    }

    /** Only after the commit: the stock is really back, so the "sold out" memory is wrong from now on. */
    private void clearGate(Released released) {
        released.standingZones().forEach(bookingGateway::clearQuantityRejected);
        released.seatsByZone().forEach(bookingGateway::clearSeatFlags);
    }
}
