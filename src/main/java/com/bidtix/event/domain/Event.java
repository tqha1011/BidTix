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
@Table(name = "events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event extends AuditableEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    /** users.id (user module) */
    @Column(name = "organizer_id", nullable = false, updatable = false)
    private Long organizerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "sale_starts_at", nullable = false)
    private Instant saleStartsAt;

    @Column(name = "sale_ends_at", nullable = false)
    private Instant saleEndsAt;

    @Column(name = "max_tickets_per_user", nullable = false)
    private int maxTicketsPerUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status = EventStatus.DRAFT;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Event(Long organizerId, Venue venue, String title, String description,
                 Instant startsAt, Instant endsAt, Instant saleStartsAt, Instant saleEndsAt,
                 int maxTicketsPerUser) {
        this.organizerId = organizerId;
        this.venue = venue;
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.saleStartsAt = saleStartsAt;
        this.saleEndsAt = saleEndsAt;
        this.maxTicketsPerUser = maxTicketsPerUser;
    }
}
