package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.domain.Order;
import com.bidtix.booking.dtos.OrderItemResponse;
import com.bidtix.booking.dtos.OrderSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.bidtix.booking.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Modifying
    @Query(
            "update Order o " +
                    "set o.status = OrderStatus.PAID, " +
                    "o.version = o.version + 1, " +
                    "o.updatedAt = :now, " +
                    "o.paidAt = :now " +
                    "where o.id = :orderId and o.status = OrderStatus.PENDING_PAYMENT "
    )
    int markOrderPaid(@Param("orderId") Long orderId, @Param("now") Instant now);


    /**
     * Ids of up to {@code limit} unpaid booking orders whose payment window is over, with id greater than
     * {@code afterId}, lowest first. A plain read, no lock: the job locks and re-checks the orders inside its
     * transaction ({@link #lockExpiredPendingOrder}). Paging by id (not by offset) means an order that cannot be
     * cancelled never makes the job read the same rows again.
     */
    @Query(value = "select id from orders " +
            "where status = 'PENDING_PAYMENT' " +
            "and expires_at <= :now and id > :afterId and source = 'BOOKING' " +
            "order by id limit :limit", nativeQuery = true)
    List<Long> findExpiredPendingOrderIds(@Param("now") Instant now, @Param("afterId") long afterId,
                                          @Param("limit") int limit);

    /**
     * Locks one order, but only if it is still an unpaid, expired booking order (auction orders are not ours: the
     * auction gives its own stock back). {@code SKIP LOCKED}: if another job or a
     * payment holds the row, this returns empty at once instead of waiting. Call it inside a transaction.
     */
    @Query(value = "select id from orders where id = :id and status = 'PENDING_PAYMENT' and source = 'BOOKING' " +
            "and expires_at <= :now for update skip locked", nativeQuery = true)
    Optional<Long> lockExpiredPendingOrder(@Param("id") long id, @Param("now") Instant now);

    /** What standing (no seat) items of these orders took from each zone's counter, lowest zone id first. */
    @Query("select i.zoneId as zoneId, sum(i.quantity) as quantity from Order o join o.items i " +
            "where o.id in :orderIds and i.seatId is null group by i.zoneId order by i.zoneId")
    List<ZoneQuantity> sumStandingQuantityByZone(@Param("orderIds") List<Long> orderIds);

    interface ZoneQuantity {
        Long getZoneId();

        Long getQuantity();
    }

    @Modifying
    @Query(
            "update Order o " +
                    "set o.status = OrderStatus.CANCELLED, " +
                    "o.version = o.version + 1, " +
                    "o.updatedAt = :now " +
                    "where o.id in :ids and o.status = OrderStatus.PENDING_PAYMENT and o.source = OrderSource.BOOKING "
    )
    int markOrderCancelled(@Param("ids") List<Long> orderId, @Param("now") Instant now);

    /**
     * Cancels an unpaid AUCTION order (PENDING_PAYMENT -&gt; CANCELLED). {@code markOrderCancelled} cannot do it: it only
     * touches booking orders, because the auction gives its own stock back. The caller MUST check the returned row count:
     * 0 means the order is not unpaid any more, which usually means it was paid at the last moment.
     */
    @Modifying
    @Query("update Order o set o.status = OrderStatus.CANCELLED, o.version = o.version + 1, o.updatedAt = :now " +
            "where o.id = :orderId and o.status = OrderStatus.PENDING_PAYMENT and o.source = OrderSource.AUCTION")
    int cancelAuctionOrder(@Param("orderId") Long orderId, @Param("now") Instant now);

    /** Read from the database (a scalar query), so it sees an update made earlier in the same transaction. */
    @Query("select o.status from Order o where o.id = :orderId")
    Optional<OrderStatus> findStatusById(@Param("orderId") Long orderId);


    @Query("select o from Order o " +
            "join User u on o.userId = u.id " +
            "where o.orderCode = :orderCode and u.publicId = :userPublicId")
    Optional<Order> findByOrderCodeAndUserId(String orderCode, UUID userPublicId);

    // Read models for the customer's own orders. Like the admin ticket list they join other modules' entities by id and
    // select straight into the DTO: one query, no N+1.

    @Query(value = """
            select new com.bidtix.booking.dtos.OrderSummaryResponse(o.orderCode, o.source, o.status, o.totalAmount,
                o.currency, o.createdAt, o.expiresAt, o.paidAt, e.publicId, e.title)
            from Order o
            join Event e on e.id = o.eventId
            join User u on u.id = o.userId
            where u.publicId = :userPublicId
            order by o.id desc
            """,
            countQuery = "select count(o) from Order o join User u on u.id = o.userId where u.publicId = :userPublicId")
    Page<OrderSummaryResponse> findSummariesOfUser(@Param("userPublicId") UUID userPublicId, Pageable pageable);

    @Query("""
            select new com.bidtix.booking.dtos.OrderSummaryResponse(o.orderCode, o.source, o.status, o.totalAmount,
                o.currency, o.createdAt, o.expiresAt, o.paidAt, e.publicId, e.title)
            from Order o
            join Event e on e.id = o.eventId
            join User u on u.id = o.userId
            where u.publicId = :userPublicId and o.orderCode = :orderCode
            """)
    Optional<OrderSummaryResponse> findSummaryOfUser(@Param("userPublicId") UUID userPublicId,
                                                     @Param("orderCode") String orderCode);

    /** The lines of an order. It does not check whose order it is: call it only for an order the caller may see. */
    @Query("""
            select new com.bidtix.booking.dtos.OrderItemResponse(z.publicId, z.name, s.publicId, s.rowLabel, s.seatNumber,
                i.quantity, i.unitPrice)
            from OrderItem i
            join TicketZone z on z.id = i.zoneId
            left join Seat s on s.id = i.seatId
            where i.order.orderCode = :orderCode
            order by i.id
            """)
    List<OrderItemResponse> findItemViews(@Param("orderCode") String orderCode);

    /**
     * Ids of up to {@code limit} unpaid booking orders of cancelled events whose orders are not cleaned up yet, with id
     * greater than {@code lastId}, lowest first. A plain read, no lock ({@link #lockPendingBookingOrders}).
     */
    @Query("select o.id " +
            "from Order o join Event e on e.id = o.eventId " +
            "where o.status = OrderStatus.PENDING_PAYMENT and o.source = OrderSource.BOOKING " +
            "and e.status = EventStatus.CANCELLED and e.ordersCleanedAt is null " +
            "and o.id > :lastId " +
            "order by o.id asc " +
            "limit :limit")
    List<Long> findOrderPendingPaymentOfCancelledEvent(@Param("limit") int limit, @Param("lastId") Long lastId);

    /**
     * Like {@link #findOrderPendingPaymentOfCancelledEvent}, for the paid orders (booking and auction) of cancelled
     * events, whose money has to be given back whatever their deadline was.
     */
    @Query("select o.id from Order o " +
            "join Event e on e.id = o.eventId " +
            "where o.status = OrderStatus.PAID and e.status = EventStatus.CANCELLED and e.ordersCleanedAt is null " +
            "and o.id > :lastId " +
            "order by o.id asc " +
            "limit :limit")
    List<Long> findOrderNeedsToRefund(@Param("limit") int limit, @Param("lastId") Long lastId);


    /**
     * Locks those of these orders that are still unpaid booking orders, lowest id first. {@code SKIP LOCKED}: an order
     * another job or a payment holds is left out instead of waited for. No deadline: this is for orders of a cancelled
     * event, which are cancelled at once. Call it inside a transaction.
     */
    @Query(value = "select id from orders where id in (:ids) and status = 'PENDING_PAYMENT' and source = 'BOOKING' " +
            "order by id for update skip locked", nativeQuery = true)
    List<Long> lockPendingBookingOrders(@Param("ids") List<Long> ids);


    /**
     * Locks one order, only if it is still PAID. {@code SKIP LOCKED}: empty when another job holds it. Call it inside a
     * transaction.
     */
    @Query(value = "select * from orders where id = :id and status = 'PAID' for update skip locked", nativeQuery = true)
    Optional<Order> lockPaidOrder(@Param("id") long id);

    /**
     * PAID -&gt; CANCELLED, for an order whose money is being given back ({@code paid_at} stays). The caller MUST check the
     * returned row count (1 = changed).
     */
    @Modifying
    @Query("update Order o set o.status = OrderStatus.CANCELLED, o.version = o.version + 1, o.updatedAt = :now " +
            "where o.id = :orderId and o.status = OrderStatus.PAID")
    int markPaidOrderCancelled(@Param("orderId") long orderId, @Param("now") Instant now);

    /**
     * Cancelled events whose orders are not marked cleaned up yet, were cancelled at or before {@code cancelledBefore},
     * whose auctions are already cleaned up, and that have no order left that is unpaid or paid, booking or auction.
     */
    @Query("select e.id from Event e where e.status = EventStatus.CANCELLED and e.ordersCleanedAt is null " +
            "and e.auctionsCleanedAt is not null and e.cancelledAt <= :cancelledBefore " +
            "and not exists (select o.id from Order o where o.eventId = e.id " +
            "and o.status in (OrderStatus.PENDING_PAYMENT, OrderStatus.PAID))")
    List<Long> findCancelledEventsWithoutOpenOrders(@Param("cancelledBefore") Instant cancelledBefore);

    /**
     * Takes the lock of one user's ticket allowance for one event, waiting while another transaction holds it. It is a
     * PostgreSQL advisory lock: no row is locked, and it is released when the transaction ends. Call it inside a
     * transaction, before {@link #countBookedTickets}. (The function returns {@code void}, hence the {@code select 1}.)
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(cast(:userId as int), cast(:eventId as int))", nativeQuery = true)
    int lockTicketAllowance(@Param("userId") long userId, @Param("eventId") long eventId);

    /** The tickets of the user's booking orders for the event that are unpaid or paid. Auction orders do not count. */
    @Query("select coalesce(sum(i.quantity), 0) from Order o join o.items i " +
            "where o.userId = :userId and o.eventId = :eventId and o.source = OrderSource.BOOKING " +
            "and o.status in (OrderStatus.PENDING_PAYMENT, OrderStatus.PAID)")
    long countBookedTickets(@Param("userId") long userId, @Param("eventId") long eventId);

    long countByStatusAndSourceAndExpiresAtBefore(OrderStatus status, OrderSource source, Instant expiredBefore);


    /** The order a user made with this Idempotency-Key. One query: the join saves reading the user first. */
    @Query("select o from Order o join User u on u.id = o.userId "
            + "where u.publicId = :userPublicId and o.idempotencyKey = :idempotencyKey")
    Optional<Order> findByUserPublicIdAndIdempotencyKey(@Param("userPublicId") UUID userPublicId,
                                                        @Param("idempotencyKey") String idempotencyKey);
}
