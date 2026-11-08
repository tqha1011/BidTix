package com.bidtix.event.domain;

import com.bidtix.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "seats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat extends AuditableEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "zone_id", nullable = false, updatable = false)
    private TicketZone zone;

    @Column(name = "row_label", nullable = false, updatable = false, length = 10)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, updatable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /** orders.id (booking module); set only while {@link SeatStatus#HELD}. */
    @Column(name = "held_by_order_id")
    private Long heldByOrderId;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Seat(TicketZone zone, String rowLabel, int seatNumber) {
        this.zone = zone;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
    }
}
