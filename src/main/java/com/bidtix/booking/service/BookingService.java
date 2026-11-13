package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderItem;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.dto.BookingTicketRequest;
import com.bidtix.booking.dto.BookingTicketResult;
import com.bidtix.booking.dto.BookingTicketResult.TicketInfo;
import com.bidtix.booking.infrastructure.BookingProperties;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.common.utils.GenerateOrderCode;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.payment.service.PaymentGateway;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingService {
    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final TicketZoneRepository ticketZoneRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final PaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final BookingProperties properties;

    /** What the first transaction hands to the payment step and to the second transaction. */
    private record Reservation(Long orderId, BookingTicketResult pending) {
    }

    /**
     * Books seats for a user. Three steps, so that the payment call never runs inside a DB transaction:
     * <ol>
     *   <li>Transaction 1 ({@link #reserve}): validate, create a PENDING_PAYMENT order and hold the seats.</li>
     *   <li>Charge through {@link PaymentGateway} (no transaction open).</li>
     *   <li>Transaction 2 ({@link #completePayment}): mark the order PAID, mark the seats SOLD and issue
     *       the tickets. Tickets exist only after a successful payment.</li>
     * </ol>
     * If the payment fails, the order stays PENDING_PAYMENT with its seats held until it expires, and the
     * result (status PENDING_PAYMENT, no tickets) tells the client so.
     */
    public BookingTicketResult bookTicket(BookingTicketRequest request, UUID userPublicId) {
        Reservation reservation = inTransaction(() -> reserve(request, userPublicId));

        boolean paid = paymentGateway.charge(reservation.pending().orderCode(), reservation.pending().totalAmount());
        if (!paid) {
            return reservation.pending();
        }

        // TODO(payment): once a real gateway is used, a failure here means the customer was charged but got
        //  no tickets (e.g. the hold expired meanwhile), so it must trigger a refund.
        return inTransaction(() -> completePayment(reservation));
    }

    private BookingTicketResult completePayment(Reservation reservation) {
        Order order = orderRepository.findById(reservation.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        order.markPaid(clock.instant());

        List<OrderItem> items = order.getItems();
        List<Long> seatIds = items.stream().map(OrderItem::getSeatId).filter(Objects::nonNull).toList();

        // The seats must still be held by this order; the row count proves it.
        int soldCount = seatRepository.markSeatsSoldByOrderId(order.getId());
        if (soldCount != seatIds.size()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "Seat hold is no longer valid");
        }

        var tickets = items.stream()
                .map(item -> new Ticket(item, order.getEventId(), order.getUserId()))
                .toList();
        ticketRepository.saveAllAndFlush(tickets);

        Map<Long, Seat> seatsById = seatRepository.findAllById(seatIds).stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity()));

        List<TicketInfo> ticketInfos = tickets.stream()
                .map(ticket -> {
                    Seat seat = ticket.getSeatId() == null ? null : seatsById.get(ticket.getSeatId());
                    return new TicketInfo(
                            ticket.getPublicId(),
                            seat == null ? null : seat.getRowLabel(),
                            seat == null ? null : seat.getSeatNumber());
                })
                .toList();

        return reservation.pending().withPayment(order.getStatus(), order.getPaidAt(), ticketInfos);
    }

    /**
     * Validates the request, creates the order and holds the seats. Runs in one transaction: if any seat
     * cannot be held, everything (including the order) rolls back.
     */
    private Reservation reserve(BookingTicketRequest request, UUID userPublicId) {
        var user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        var event = eventRepository.findByPublicId(request.eventPublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        var zone = ticketZoneRepository.findByPublicId(request.zonePublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
        validateZoneAndSeats(zone, request);
        Map<UUID, Seat> seatsByPublicId = checkAvailableSeats(zone.getId(), request);

        // create order & order items
        var order = new Order(user.getId(), event.getId(), GenerateOrderCode.generate(), OrderSource.BOOKING,
                clock.instant().plus(properties.orderExpirationMinutes(), java.time.temporal.ChronoUnit.MINUTES));
        var orderItems = createOrderItems(zone, request, seatsByPublicId, order);

        // save DB changes immediately: the seat update below needs the order id
        orderRepository.saveAndFlush(order);

        // ascending, so rows are always locked in the same order
        List<Long> seatIds = orderItems.stream()
                .map(OrderItem::getSeatId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        int heldCount = seatRepository.updateSeatHoldBySeatIds(seatIds, order.getId(), order.getExpiresAt());
        if (heldCount != seatIds.size()) {
            // Another request took a seat between our read and this update. Find out which ones, then
            // throw so the whole transaction (order included) rolls back.
            Set<UUID> heldByThisOrder = seatRepository.findPublicIdsHeldByOrderId(order.getId());
            List<UUID> lostSeats = request.seatPublicIds().stream()
                    .filter(seatPublicId -> !heldByThisOrder.contains(seatPublicId))
                    .toList();
            throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE,
                    "Some seats are unavailable", Map.of("seatPublicIds", lostSeats));
        }

        // Read the lazy venue here, inside the transaction: the result is used after it ends.
        var pending = new BookingTicketResult(
                order.getOrderCode(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getExpiresAt(),
                order.getPaidAt(),
                user.getFullName(),
                event.getTitle(),
                event.getVenue().getAddress(),
                event.getStartsAt(),
                event.getEndsAt(),
                zone.getZoneType(),
                List.of());
        return new Reservation(order.getId(), pending);
    }

    private void validateZoneAndSeats(TicketZone zone, BookingTicketRequest request) {
        if (zone.getZoneType() == ZoneType.SEATED) {
            if (request.seatPublicIds() == null || request.seatPublicIds().isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "Seat must be specified for seated zones");
            }
        } else {
            if (request.quantity() == null || request.quantity() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "Quantity must be specified for general admission zones");
            }
        }
    }

    /**
     * Checks that every requested seat is available and returns the available seats of the zone
     * keyed by public id, so callers can resolve a requested public id to its {@link Seat} in O(1).
     * Every id in the request is guaranteed to be present in the returned map.
     */
    private Map<UUID, Seat> checkAvailableSeats(Long zoneId, BookingTicketRequest request) {
        Set<UUID> requestedSeats = new LinkedHashSet<>(request.seatPublicIds());
        if (requestedSeats.size() != request.seatPublicIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Booked duplicate seat");
        }

        // Fetch available seats & index them by public id
        Map<UUID, Seat> availableSeats = seatRepository.findAllAvailableSeatByTicketZoneId(zoneId)
                .stream()
                .collect(Collectors.toMap(Seat::getPublicId, Function.identity()));

        List<UUID> unavailableSeats = requestedSeats.stream()
                .filter(seatId -> !availableSeats.containsKey(seatId))
                .toList();

        if (!unavailableSeats.isEmpty()) {
            throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE,
                    "Some seats are unavailable", Map.of("seatPublicIds", unavailableSeats));
        }
        return availableSeats;
    }

    private List<OrderItem> createOrderItems(TicketZone zone, BookingTicketRequest request, Map<UUID, Seat> seats, Order order) {
        return request.seatPublicIds().stream()
                .map(seatPublicId -> {
                    Seat seat = seats.get(seatPublicId);
                    if (seat == null) {
                        throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE, "Seat not available: " + seatPublicId);
                    }
                    return order.addItem(zone.getId(), seat.getId(), 1, zone.getPrice());
                }).toList();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> work.get()));
    }
}
