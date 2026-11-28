package com.bidtix.booking.service;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderItem;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketQuantityResult;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.dtos.BookingTicketSeatsResult;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.BookingProperties;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.common.utils.GenerateOrderCode;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.*;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
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
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final BookingProperties properties;
    private final BookingGateway gateway;

    /**
     * Books seats (SEATED zone) or a quantity (GENERAL_ADMISSION zone) for a user: validates, creates a PENDING_PAYMENT
     * order and takes the stock (holds the seats, or subtracts the quantity from the zone counter), in one transaction
     * ({@link #reserve}). It does NOT take payment: the customer pays afterwards (the payment module, see
     * {@code PayableOrders}), which marks the order PAID and issues the tickets. The returned result has status
     * PENDING_PAYMENT and no tickets, and the stock stays taken until {@code expiresAt}; an order nobody pays is
     * cancelled by {@code OrderCancellationJob}, which gives the stock back.
     * <p>
     * The order keeps the {@code Idempotency-Key}, one per user and key. A repeat that reaches this method (the filter
     * no longer knew the key) gets the order it made: every refusal, the unique index included, looks for it first
     * ({@link #findExisting}), and a request that succeeds pays nothing for this.
     */
    public BaseBookingTicketResult bookTicket(BookingTicketRequest request, UUID userPublicId, String idempotencyKey) {
        try{
            rejectIfKnownUnavailable(request);
        }
        catch (BusinessException e){
            return findExisting(userPublicId,request,idempotencyKey)
                    .orElseThrow(() -> e);
        }
        try {
            return inTransaction(() -> reserve(request, userPublicId, idempotencyKey));
        } catch (BusinessException e) {
            var existing = findExisting(userPublicId,request,idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            rememberRejection(request, e);
            throw e;
        }
        catch (DataIntegrityViolationException e){
            return findExisting(userPublicId,request,idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Fast reject before the booking transaction: if a recent request already learned that this quantity or these seats
     * cannot be had, say so without taking any lock (the caller still looks for the request's own order, one read).
     * This only ever says no. A missing, expired or
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

    /**
     * Validates the request, creates the order and takes the stock. Runs in one transaction: if the stock
     * cannot be taken, everything (including the order) rolls back.
     */
    private BaseBookingTicketResult reserve(BookingTicketRequest request, UUID userPublicId, String idempotencyKey) {
        Instant now = clock.instant();
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

        if(!event.getStatus().equals(EventStatus.PUBLISHED)) {
            throw new BusinessException(ErrorCode.EVENT_NOTFOUND);
        }

        if(now.isBefore(event.getSaleStartsAt()) || !now.isBefore(event.getSaleEndsAt())) {
            throw new BusinessException(ErrorCode.EVENT_NOT_ON_SALE);
        }
        validateZoneAndSeats(zone, request);
        checkTicketLimit(user, event, zone, request);

        return zone.getZoneType() == ZoneType.SEATED
                ? reserveSeats(user, event, zone, request, idempotencyKey)
                : reserveQuantity(user, event, zone, request.quantity(), idempotencyKey);
    }

    /**
     * Refuses a request that would take the user past the event's {@code max_tickets_per_user}. The lock comes first:
     * the user's other requests for this event wait until this transaction ends, so what they count includes the order
     * made here. Other users are not held up.
     */
    private void checkTicketLimit(User user, Event event, TicketZone zone, BookingTicketRequest request) {
        int requested = zone.getZoneType() == ZoneType.SEATED ? request.seatPublicIds().size() : request.quantity();
        orderRepository.lockTicketAllowance(user.getId(), event.getId());
        long booked = orderRepository.countBookedTickets(user.getId(), event.getId());
        if (booked + requested > event.getMaxTicketsPerUser()) {
            throw new BusinessException(ErrorCode.TICKET_LIMIT_EXCEEDED, "At most " + event.getMaxTicketsPerUser()
                    + " tickets per user for this event, and you already have " + booked);
        }
    }

    /**
     * The order this user already made with this key, if any: what a request that is about to be refused gets instead,
     * when it is a repeat of the one that made the order. Throws {@code IDEMPOTENCY_KEY_REUSED} if that order was made
     * by a different request. Without an order it costs one query.
     */
    private Optional<BaseBookingTicketResult> findExisting(UUID userPublicId,BookingTicketRequest request ,
                                                           String idempotencyKey) {
        return inTransaction(() -> orderRepository.findByUserPublicIdAndIdempotencyKey(userPublicId, idempotencyKey)
                .map(order -> {
                    requireSameRequest(order, request);
                    return toResult(order);
                }));
    }

    /**
     * An order that already exists, in the shape {@link #bookTicket} answers with: its state NOW, so a retry may find it
     * PAID (with its tickets) or CANCELLED. Call it inside a transaction: the items and the venue are read lazily.
     */
    private BaseBookingTicketResult toResult(Order order) {
        var user = userRepository.findById(order.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        var event = eventRepository.findById(order.getEventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        // a booking order's items are all of one zone
        var zone = ticketZoneRepository.findById(order.getItems().getFirst().getZoneId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
        // tickets are shown for a paid order only: those of a cancelled one are no longer good
        List<Ticket> tickets = order.getStatus() == OrderStatus.PAID
                ? ticketRepository.findByOrderIdOrderById(order.getId())
                : List.of();

        if (zone.getZoneType() == ZoneType.SEATED) {
            return new BookingTicketSeatsResult(
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
                    toSeatTickets(tickets));
        }
        return new BookingTicketQuantityResult(
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
                order.getItems().stream().mapToInt(OrderItem::getQuantity).sum(),
                tickets.stream()
                        .map(ticket -> new BookingTicketQuantityResult.TicketQuantityInfo(ticket.getPublicId()))
                        .toList());
    }

    /** One query for all the seats of the tickets. */
    private List<BookingTicketSeatsResult.TicketSeatsInfo> toSeatTickets(List<Ticket> tickets) {
        Map<Long, Seat> seats = seatRepository.findAllById(tickets.stream().map(Ticket::getSeatId).toList()).stream()
                .collect(Collectors.toMap(Seat::getId, Function.identity()));
        return tickets.stream()
                .map(ticket -> {
                    Seat seat = seats.get(ticket.getSeatId());
                    return new BookingTicketSeatsResult.TicketSeatsInfo(ticket.getPublicId(), seat.getRowLabel(),
                            seat.getSeatNumber());
                })
                .toList();
    }

    private void requireSameRequest(Order order, BookingTicketRequest request) {
        boolean same = false;
        var event = eventRepository.findById(order.getEventId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        var zone = ticketZoneRepository.findById(order.getItems().getFirst().getZoneId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));

        if(event.getPublicId().equals(request.eventPublicId())
                && zone.getPublicId().equals(request.zonePublicId())) {
            if(zone.getZoneType() == ZoneType.SEATED) {
                var seatIds = order.getItems().stream()
                        .map(OrderItem::getSeatId)
                        .collect(Collectors.toSet());

                var requested = request.seatPublicIds();
                // as many ids as the order has seats, so no list, or a seat named twice, is another request
                if (requested != null && requested.size() == seatIds.size()) {
                    var seatRequestIds = seatRepository.getListIdsByPublicIdsAndZoneId(new HashSet<>(requested),
                            zone.getId());
                    same = seatIds.size() == seatRequestIds.size() && seatIds.containsAll(seatRequestIds);
                }
            } else {
                var total = order.getItems().stream()
                        .mapToInt(OrderItem::getQuantity)
                        .sum();
                boolean namesNoSeats = request.seatPublicIds() == null || request.seatPublicIds().isEmpty();
                same = namesNoSeats && Integer.valueOf(total).equals(request.quantity());   // quantity may be null
            }
        }

        if(!same){
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused with a different request");
        }
    }

    private BookingTicketSeatsResult reserveSeats(User user, Event event, TicketZone zone, BookingTicketRequest request,
                                                  String idempotencyKey) {
        Map<UUID, Seat> seatsByPublicId = checkAvailableSeats(zone.getId(), request);

        // create order & order items
        var order = newOrder(user, event,idempotencyKey);
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
        return new BookingTicketSeatsResult(
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
    }

    /**
     * Takes {@code quantity} tickets from the zone counter with one conditional UPDATE (see
     * {@code TicketZoneRepository.takeQuantity}). The UPDATE locks the counter row until commit and every buyer
     * of the zone queues on it, so it is the LAST write: everything else, including the lazy venue read, is
     * done before it, and only the commit follows.
     */
    private BookingTicketQuantityResult reserveQuantity(User user, Event event, TicketZone zone, int quantity,
                                                        String idempotencyKey) {
        var order = newOrder(user, event, idempotencyKey);
        order.addItem(zone.getId(), null, quantity, zone.getPrice());
        String address = event.getVenue().getAddress();   // lazy: read it before the counter lock is taken

        orderRepository.saveAndFlush(order);

        if (ticketZoneRepository.takeQuantity(zone.getId(), quantity) == 0) {
            // Sold out, or fewer left than asked. Only a hint for the client: the UPDATE alone decided.
            Integer left = ticketZoneRepository.findAvailableQuantity(zone.getId());
            throw new BusinessException(ErrorCode.ZONE_SOLD_OUT, "Not enough tickets left in this zone",
                    Map.of("availableQuantity", left == null ? 0 : left));
        }

        return new BookingTicketQuantityResult(
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
    }

    
    private Order newOrder(User user, Event event, String idempotencyKey) {
        return new Order(user.getId(), event.getId(), GenerateOrderCode.generate(), OrderSource.BOOKING,
                clock.instant().plus(properties.orderExpirationMinutes(), java.time.temporal.ChronoUnit.MINUTES), idempotencyKey);
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
        Map<UUID, Seat> availableSeats = seatRepository.findAvailableSeatRequestByTicketZoneIdAndPublicIds(zoneId, requestedSeats)
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
