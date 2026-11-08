package com.bidtix.auction.domain;

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

import java.util.UUID;

/** Append-only: only accepted bids are stored. */
@Getter
@Entity
@Table(name = "bids")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bid extends BaseEntity {

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false, updatable = false)
    private Auction auction;

    /** users.id (user module) */
    @Column(name = "bidder_id", nullable = false, updatable = false)
    private Long bidderId;

    /** VND */
    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    public Bid(Auction auction, Long bidderId, long amount) {
        this.auction = auction;
        this.bidderId = bidderId;
        this.amount = amount;
    }
}
