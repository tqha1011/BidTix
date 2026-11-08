package com.bidtix.booking.domain;

import com.bidtix.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Issued only after a successful payment. {@code publicId} is the QR payload. */
@Getter
@Entity
@Table(name = "tickets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends AuditableEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
    private OrderItem orderItem;

    /** events.id (event module) */
    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    /** ticket_zones.id (event module) */
    @Column(name = "zone_id", nullable = false, updatable = false)
    private Long zoneId;

    /** seats.id (event module); {@code null} for GENERAL_ADMISSION. */
    @Column(name = "seat_id", updatable = false)
    private Long seatId;

    /** users.id (user module) */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status = TicketStatus.VALID;

    /** Check-in time; set exactly when the status is {@link TicketStatus#USED}. */
    @Column(name = "used_at")
    private Instant usedAt;

    public Ticket(OrderItem orderItem, Long eventId, Long ownerId) {
        this.order = orderItem.getOrder();
        this.orderItem = orderItem;
        this.eventId = eventId;
        this.zoneId = orderItem.getZoneId();
        this.seatId = orderItem.getSeatId();
        this.ownerId = ownerId;
    }
}
