package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.AuctionOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuctionOfferRepository extends JpaRepository<AuctionOffer, Long> {

    /**
     * Ids of up to {@code limit} PENDING offers whose deadline has passed, with id greater than {@code afterId}, lowest
     * first. A plain read, no lock: the expiry job locks and re-checks each offer inside its own transaction
     * ({@link #lockExpiredPendingOffer}).
     */
    @Query(value = "select id from auction_offers where status = 'PENDING' and expires_at <= :now and id > :afterId " +
            "order by id limit :limit", nativeQuery = true)
    List<Long> findExpiredPendingOfferIds(@Param("now") Instant now, @Param("afterId") long afterId,
                                          @Param("limit") int limit);

    /**
     * Locks one offer, but only if it is still PENDING and past its deadline. {@code SKIP LOCKED}: if another job holds
     * the row this returns empty at once instead of waiting. Call it inside a transaction.
     */
    @Query(value = "select * from auction_offers where id = :id and status = 'PENDING' and expires_at <= :now " +
            "for update skip locked", nativeQuery = true)
    Optional<AuctionOffer> lockExpiredPendingOffer(@Param("id") long id, @Param("now") Instant now);

    /** PENDING -&gt; EXPIRED. The caller MUST check the returned row count (1 = changed). */
    @Modifying
    @Query("update AuctionOffer o set o.status = com.bidtix.auction.domain.OfferStatus.EXPIRED, o.updatedAt = :now " +
            "where o.id = :offerId and o.status = com.bidtix.auction.domain.OfferStatus.PENDING")
    int markExpired(@Param("offerId") Long offerId, @Param("now") Instant now);

    /** PENDING -&gt; ACCEPTED (the offer was paid). The caller MUST check the returned row count (1 = changed). */
    @Modifying
    @Query("update AuctionOffer o set o.status = com.bidtix.auction.domain.OfferStatus.ACCEPTED, o.updatedAt = :now " +
            "where o.id = :offerId and o.status = com.bidtix.auction.domain.OfferStatus.PENDING")
    int markAccepted(@Param("offerId") Long offerId, @Param("now") Instant now);


    @Modifying
    @Query("update AuctionOffer o set o.status = com.bidtix.auction.domain.OfferStatus.ACCEPTED, o.updatedAt = :now " +
            "where o.orderId = :orderId and o.status = com.bidtix.auction.domain.OfferStatus.PENDING and o.expiresAt > :now")
    int markOfferAcceptedByOrderId(@Param("orderId") Long orderId, @Param("now") Instant now);
}
