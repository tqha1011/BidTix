package com.bidtix.booking.domain;

import com.bidtix.common.domain.AuditableEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Getter
@Entity
@Table(name = "orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends AuditableEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    /** users.id (user module) */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** events.id (event module) */
    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 20)
    private OrderSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    /** Unique order code for display and search. */
    @Column(name = "order_code", nullable = false, updatable = false, length = 20)
    private String orderCode;

    /** VND; always the sum of the items. */
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    /** Payment deadline. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public Order(Long userId, Long eventId,String orderCode ,OrderSource source, Instant expiresAt) {
        this.userId = userId;
        this.eventId = eventId;
        this.source = source;
        this.orderCode = orderCode;
        this.expiresAt = expiresAt;
    }

    public OrderItem addItem(Long zoneId, Long seatId, int quantity, long unitPrice) {
        OrderItem item = new OrderItem(this, zoneId, seatId, quantity, unitPrice);
        items.add(item);
        totalAmount = Math.addExact(totalAmount, item.getSubtotal());
        return item;
    }

    /** PENDING_PAYMENT -> PAID. Concurrent changes are caught by {@code @Version} on flush. */
    public void markPaid(Instant now) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Only a pending order can be paid, but it is " + status);
        }
        this.status = OrderStatus.PAID;
        this.paidAt = now;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }
}
