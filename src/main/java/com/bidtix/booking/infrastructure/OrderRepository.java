package com.bidtix.booking.infrastructure;

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
}
