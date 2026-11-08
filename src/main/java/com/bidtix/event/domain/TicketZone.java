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

@Getter
@Entity
@Table(name = "ticket_zones")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketZone extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private Event event;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false, updatable = false, length = 20)
    private ZoneType zoneType;

    /** VND */
    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    /** GENERAL_ADMISSION only; {@code null} for SEATED zones (seats are tracked individually). */
    @Column(name = "available_quantity")
    private Integer availableQuantity;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public TicketZone(Event event, String name, ZoneType zoneType, long price, int capacity) {
        this.event = event;
        this.name = name;
        this.zoneType = zoneType;
        this.price = price;
        this.capacity = capacity;
        this.availableQuantity = zoneType == ZoneType.GENERAL_ADMISSION ? capacity : null;
    }
}
