package com.bidtix.booking.domain;

import com.bidtix.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    /** ticket_zones.id (event module) */
    @Column(name = "zone_id", nullable = false, updatable = false)
    private Long zoneId;

    /** seats.id (event module); SEATED zones only. */
    @Column(name = "seat_id", updatable = false)
    private Long seatId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    /** Price snapshot at order time (VND). */
    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    /** Created through {@link Order#addItem}. */
    OrderItem(Order order, Long zoneId, Long seatId, int quantity, long unitPrice) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (seatId != null && quantity != 1) throw new IllegalArgumentException("a seated item has quantity 1");
        this.order = order;
        this.zoneId = zoneId;
        this.seatId = seatId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getSubtotal() {
        return Math.multiplyExact(unitPrice, quantity);
    }
}
