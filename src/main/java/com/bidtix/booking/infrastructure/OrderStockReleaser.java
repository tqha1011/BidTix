package com.bidtix.booking.infrastructure;

import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.SeatRepository.HeldSeat;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gives back the stock of unpaid booking orders that are being cancelled (seats HELD -> AVAILABLE, standing quantity
 * added back to the zone counter). Shared by the jobs that cancel such orders.
 * <p>
 * Two steps, around the caller's commit: {@link #release} inside the transaction that has just marked the orders
 * CANCELLED, so an order is never cancelled without its stock coming back; {@link #clearGate} after that commit, because
 * clearing earlier would let a request set a {@link BookingGateway} flag again while the stock is still taken.
 * It does not check the orders itself: call it once, only for orders this transaction cancelled, or their stock comes
 * back twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderStockReleaser {

    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;
    private final TicketZoneRepository zoneRepository;
    private final BookingGateway bookingGateway;

    /** What a release gave back: how many orders, and which gate flags are now out of date. */
    record Released(int orders, Map<UUID, List<UUID>> seatsByZone, List<UUID> standingZones) {
        static final Released NONE = new Released(0, Map.of(), List.of());
    }

    /** Inside the caller's transaction. */
    Released release(List<Long> orderIds) {
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
        return new Released(orderIds.size(), seatsByZone, standingZones);
    }

    /** Only after the commit: the stock is really back, so the "sold out" memory is wrong from now on. */
    void clearGate(Released released) {
        released.standingZones().forEach(bookingGateway::clearQuantityRejected);
        released.seatsByZone().forEach(bookingGateway::clearSeatFlags);
    }
}
