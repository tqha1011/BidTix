package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
}
