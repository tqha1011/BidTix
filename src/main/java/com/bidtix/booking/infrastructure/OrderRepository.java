package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
    int markOrderPaid(@Param("id") Long orderId, @Param("now") Instant now);


    @Query(
                    "select o " +
                    "from Order o " +
                    "where o.status = :status and o.expiresAt <= :now "
    )
    Slice<Order> findOrdersExpiredToCancel(@Param("status") OrderStatus status,
                                                @Param("now") Instant now,
                                                @Param("pageable") Pageable pageable);



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
