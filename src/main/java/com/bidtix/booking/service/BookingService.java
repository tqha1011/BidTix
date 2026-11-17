package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderItem;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketQuantityResult;
import com.bidtix.booking.dtos.BookingTicketQuantityResult.TicketQuantityInfo;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.dtos.BookingTicketSeatsResult;
import com.bidtix.booking.dtos.BookingTicketSeatsResult.TicketSeatsInfo;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.BookingProperties;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.common.utils.GenerateOrderCode;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.payment.service.PaymentGateway;
import com.bidtix.user.domain.User;
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
    private final BookingGateway gateway;

    /** What the first transaction hands to the payment step and to the second transaction. */
    private record Reservation(Long orderId, BaseBookingTicketResult pending) {
    }

    /**
     * Books seats (SEATED zone) or a quantity (GENERAL_ADMISSION zone) for a user. Three steps, so that the
     * payment call never runs inside a DB transaction:
     * <ol>
     *   <li>Transaction 1 ({@link #reserve}): validate, create a PENDING_PAYMENT order and take the stock:
     *       hold the seats, or subtract the quantity from the zone counter.</li>
     *   <li>Charge through {@link PaymentGateway} (no transaction open).</li>
     *   <li>Transaction 2 ({@link #completePayment}): mark the order PAID and issue the tickets (seats also
     *       go HELD -> SOLD). Tickets exist only after a successful payment.</li>
     * </ol>
     * If the payment fails, the order stays PENDING_PAYMENT with its stock taken until it expires, and the
     * result (status PENDING_PAYMENT, no tickets) tells the client so.
     */
    public BaseBookingTicketResult bookTicket(BookingTicketRequest request, UUID userPublicId) {
        rejectIfKnownUnavailable(request);

        Reservation reservation;
        try {
            reservation = inTransaction(() -> reserve(request, userPublicId));
        } catch (BusinessException e) {
            rememberRejection(request, e);
            throw e;
        }

        boolean paid = paymentGateway.charge(reservation.pending().orderCode(), reservation.pending().totalAmount());
        if (!paid) {
            return reservation.pending();
        }

        // TODO(payment): once a real gateway is used, a failure here means the customer was charged but got
        //  no tickets (e.g. the hold expired meanwhile), so it must trigger a refund.
        return inTransaction(() -> completePayment(reservation));
    }

    /**
     * Fast reject before any database work: if a recent request already learned that this quantity or these seats
     * cannot be had, say so without opening a transaction. This only ever says no. A missing, expired or
     * unreachable flag lets the request through, and the conditional UPDATE in {@link #reserve} still decides.
     * A flag can be stale for at most {@code bidtix.booking.gate-ttl-seconds} after stock comes back.
     */
    private void rejectIfKnownUnavailable(BookingTicketRequest request) {
        UUID zone = request.zonePublicId();
        if (zone == null) {
            return;
        }
        int max = properties.maxTicketsPerOrder();
        List<UUID> seats = request.seatPublicIds();
        if (seats != null && !seats.isEmpty()) {
            if (seats.size() > max) {
                return;   // invalid request: let validation in reserve() answer it
            }
            List<UUID> flagged = gateway.flaggedSeats(zone, seats);
            if (!flagged.isEmpty()) {
                throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE,
                        "Some seats are unavailable", Map.of("seatPublicIds", flagged));
            }
        } else {
            Integer quantity = request.quantity();
            if (quantity != null && quantity > 0 && quantity <= max && !gateway.mayTakeQuantity(zone, quantity)) {
                throw new BusinessException(ErrorCode.ZONE_SOLD_OUT, "Not enough tickets left in this zone");
            }
        }
    }

    /** The database said no: tell the gate, so the next requests for the same thing skip the database. */
    private void rememberRejection(BookingTicketRequest request, BusinessException e) {
        UUID zone = request.zonePublicId();
        if (zone == null) {
            return;
        }
        switch (e.getErrorCode()) {
            case ZONE_SOLD_OUT -> {
                if (request.quantity() != null) {
                    gateway.setQuantityRejected(zone, request.quantity());
                }
            }
            case SEAT_UNAVAILABLE -> {
                if (e.getProperties().get("seatPublicIds") instanceof List<?> ids) {
                    gateway.setSeatFlagged(zone, ids.stream()
                            .filter(UUID.class::isInstance).map(UUID.class::cast).toList());
                }
            }
            default -> {
            }
        }
    }

    private BaseBookingTicketResult completePayment(Reservation reservation) {
        var paid = orderRepository.markOrderPaid(reservation.orderId, clock.instant());

        if(paid == 0){
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }

        Order order = orderRepository.findById(reservation.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOTFOUND));

        return switch (reservation.pending()) {
            case BookingTicketSeatsResult pending -> completeSeats(order, pending);
            case BookingTicketQuantityResult pending -> completeQuantity(order, pending);
        };
    }

    private BookingTicketSeatsResult completeSeats(Order order, BookingTicketSeatsResult pending) {
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

        List<TicketSeatsInfo> ticketInfos = tickets.stream()
                .map(ticket -> {
                    Seat seat = ticket.getSeatId() == null ? null : seatsById.get(ticket.getSeatId());
                    return new TicketSeatsInfo(
                            ticket.getPublicId(),
                            seat == null ? null : seat.getRowLabel(),
                            seat == null ? null : seat.getSeatNumber());
                })
                .toList();

        return pending.withPayment(order.getStatus(), order.getPaidAt(), ticketInfos);
    }

    /**
     * Nothing to mark SOLD: the quantity was already subtracted when the order was reserved. One ticket per
     * unit, all pointing at the same order item (each person at the door needs their own QR code).
     */
    private BookingTicketQuantityResult completeQuantity(Order order, BookingTicketQuantityResult pending) {
        var tickets = order.getItems().stream()
                .flatMap(item -> java.util.stream.IntStream.range(0, item.getQuantity())
                        .mapToObj(i -> new Ticket(item, order.getEventId(), order.getUserId())))
                .toList();
        ticketRepository.saveAllAndFlush(tickets);

        List<TicketQuantityInfo> ticketInfos = tickets.stream()
                .map(ticket -> new TicketQuantityInfo(ticket.getPublicId()))
                .toList();
        return pending.withPayment(order.getStatus(), order.getPaidAt(), ticketInfos);
    }

    /**
     * Validates the request, creates the order and takes the stock. Runs in one transaction: if the stock
     * cannot be taken, everything (including the order) rolls back.
     */
    private Reservation reserve(BookingTicketRequest request, UUID userPublicId) {
        var user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        var event = eventRepository.findByPublicId(request.eventPublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        var zone = ticketZoneRepository.findByPublicId(request.zonePublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
        // a zone of another event is "not found" for this event
        if (!zone.getEvent().getId().equals(event.getId())) {
            throw new BusinessException(ErrorCode.ZONE_NOTFOUND);
        }
        validateZoneAndSeats(zone, request);

        return zone.getZoneType() == ZoneType.SEATED
                ? reserveSeats(user, event, zone, request)
                : reserveQuantity(user, event, zone, request.quantity());
    }

    private Reservation reserveSeats(User user, Event event, TicketZone zone, BookingTicketRequest request) {
        Map<UUID, Seat> seatsByPublicId = checkAvailableSeats(zone.getId(), request);

        // create order & order items
        var order = newOrder(user, event);
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
        var pending = new BookingTicketSeatsResult(
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

    /**
     * Takes {@code quantity} tickets from the zone counter with one conditional UPDATE (see
     * {@code TicketZoneRepository.takeQuantity}). The UPDATE locks the counter row until commit and every buyer
     * of the zone queues on it, so it is the LAST write: everything else, including the lazy venue read, is
     * done before it, and only the commit follows.
     */
    private Reservation reserveQuantity(User user, Event event, TicketZone zone, int quantity) {
        var order = newOrder(user, event);
        order.addItem(zone.getId(), null, quantity, zone.getPrice());
        String address = event.getVenue().getAddress();   // lazy: read it before the counter lock is taken

        orderRepository.saveAndFlush(order);

        if (ticketZoneRepository.takeQuantity(zone.getId(), quantity) == 0) {
            // Sold out, or fewer left than asked. Only a hint for the client: the UPDATE alone decided.
            Integer left = ticketZoneRepository.findAvailableQuantity(zone.getId());
            throw new BusinessException(ErrorCode.ZONE_SOLD_OUT, "Not enough tickets left in this zone",
                    Map.of("availableQuantity", left == null ? 0 : left));
        }

        var pending = new BookingTicketQuantityResult(
                order.getOrderCode(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getExpiresAt(),
                order.getPaidAt(),
                user.getFullName(),
                event.getTitle(),
                address,
                event.getStartsAt(),
                event.getEndsAt(),
                zone.getZoneType(),
                quantity,
                List.of());
        return new Reservation(order.getId(), pending);
    }

    private Order newOrder(User user, Event event) {
        return new Order(user.getId(), event.getId(), GenerateOrderCode.generate(), OrderSource.BOOKING,
                clock.instant().plus(properties.orderExpirationMinutes(), java.time.temporal.ChronoUnit.MINUTES));
    }

    private void validateZoneAndSeats(TicketZone zone, BookingTicketRequest request) {
        int max = properties.maxTicketsPerOrder();
        if (zone.getZoneType() == ZoneType.SEATED) {
            if (request.seatPublicIds() == null || request.seatPublicIds().isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "Seat must be specified for seated zones");
            }
            if (request.seatPublicIds().size() > max) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "At most " + max + " tickets per order");
            }
        } else {
            if (request.seatPublicIds() != null && !request.seatPublicIds().isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "General admission zones are booked by quantity, not by seat");
            }
            if (request.quantity() == null || request.quantity() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "Quantity must be specified for general admission zones");
            }
            if (request.quantity() > max) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "At most " + max + " tickets per order");
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
