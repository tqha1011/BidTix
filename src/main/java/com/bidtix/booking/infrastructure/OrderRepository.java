package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

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
     * Locks and returns the ids of up to {@code limit} orders whose payment window is over, lowest id first.
     * {@code SKIP LOCKED} makes a second app instance (or a second run) take other rows instead of waiting, so
     * two jobs never cancel, and give the stock back for, the same order. Call it inside a transaction: the
     * locks last until it ends.
     */
    @Query(value = "select id from orders where status = 'PENDING_PAYMENT' and expires_at <= :now " +
            "order by id limit :limit for update skip locked", nativeQuery = true)
    List<Long> lockExpiredPendingOrderIds(@Param("now") Instant now, @Param("limit") int limit);

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
                    "where o.id in :ids and o.status = OrderStatus.PENDING_PAYMENT "
    )
    int markOrderCancelled(@Param("ids") List<Long> orderId, @Param("now") Instant now);
}
