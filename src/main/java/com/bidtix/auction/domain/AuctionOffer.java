package com.bidtix.auction.domain;

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

/**
 * Right to buy the auctioned ticket at the bid price before {@code expiresAt}.
 * Rank 1 is the winner; if they do not pay, the next-highest distinct bidder gets the next rank.
 */
@Getter
@Entity
@Table(name = "auction_offers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuctionOffer extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false, updatable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bid_id", nullable = false, updatable = false)
    private Bid bid;

    /** users.id (user module) */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "offer_rank", nullable = false, updatable = false)
    private int offerRank;

    /** orders.id (booking module); the AUCTION-source order created with this offer. */
    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OfferStatus status = OfferStatus.PENDING;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    public AuctionOffer(Auction auction, Bid bid, int offerRank, Long orderId, Instant expiresAt) {
        this.auction = auction;
        this.bid = bid;
        this.userId = bid.getBidderId();
        this.offerRank = offerRank;
        this.orderId = orderId;
        this.expiresAt = expiresAt;
    }
}
