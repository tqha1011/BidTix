package com.bidtix.auction.domain;

import com.bidtix.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** English auction selling exactly one ticket (a seat, or one GENERAL_ADMISSION unit). */
@Getter
@Entity
@Table(name = "auctions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auction extends AuditableEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    /** events.id (event module) */
    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    /** ticket_zones.id (event module) */
    @Column(name = "zone_id", nullable = false, updatable = false)
    private Long zoneId;

    /** seats.id (event module); {@code null} when auctioning one GENERAL_ADMISSION unit. */
    @Column(name = "seat_id", updatable = false)
    private Long seatId;

    @Column(name = "start_price", nullable = false, updatable = false)
    private long startPrice;

    @Column(name = "min_increment", nullable = false, updatable = false)
    private long minIncrement;

    /** Hidden floor; if the final price is below it the auction fails. */
    @Column(name = "reserve_price", updatable = false)
    private Long reservePrice;

    /** Highest accepted bid (denormalized); {@code null} until the first bid. */
    @Column(name = "current_price")
    private Long currentPrice;

    /** users.id (user module) */
    @Column(name = "leading_bidder_id")
    private Long leadingBidderId;

    @Column(name = "bid_count", nullable = false)
    private int bidCount;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Pushed forward by anti-sniping. */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "anti_snipe_window_sec", nullable = false)
    private int antiSnipeWindowSec;

    @Column(name = "extension_sec", nullable = false)
    private int extensionSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuctionStatus status = AuctionStatus.SCHEDULED;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public Auction(Long eventId, Long zoneId, Long seatId,
                   long startPrice, long minIncrement, Long reservePrice,
                   Instant startsAt, Instant endsAt, int antiSnipeWindowSec, int extensionSec) {
        this.eventId = eventId;
        this.zoneId = zoneId;
        this.seatId = seatId;
        this.startPrice = startPrice;
        this.minIncrement = minIncrement;
        this.reservePrice = reservePrice;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.antiSnipeWindowSec = antiSnipeWindowSec;
        this.extensionSec = extensionSec;
    }
}
