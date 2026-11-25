package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.dtos.LeaderboardEntryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    /** A page of auctions in any of these statuses. A plain read, no lock. */
    Page<Auction> findByStatusIn(Collection<AuctionStatus> statuses, Pageable pageable);

    /** Same, for one event (internal id). */
    Page<Auction> findByEventIdAndStatusIn(Long eventId, Collection<AuctionStatus> statuses, Pageable pageable);

    /**
     * Opens up to {@code limit} auctions whose start time has come (SCHEDULED -&gt; LIVE), lowest id first, and returns how
     * many it opened. One statement: the rows are picked with {@code FOR UPDATE SKIP LOCKED}, so a second app instance
     * (or a second run) takes other rows instead of waiting, and the {@code status = 'SCHEDULED'} condition means an
     * auction that was opened or cancelled meanwhile is never touched. A bulk update does not run
     * {@code @UpdateTimestamp}, so {@code updated_at} is set here. Call it inside a transaction.
     */
    @Modifying
    @Query(value = "update auctions set status = 'LIVE', version = version + 1, updated_at = :now " +
            "where status = 'SCHEDULED' and id in (" +
            "select id from auctions where status = 'SCHEDULED' and starts_at <= :now " +
            "order by id limit :limit for update skip locked)", nativeQuery = true)
    int openDueAuctions(@Param("now") Instant now, @Param("limit") int limit);


    /**
     * Ids of up to {@code limit} LIVE auctions whose end time has passed, with id greater than {@code afterId}, lowest
     * first. A plain read, no lock: the closing job locks and re-checks each auction inside its own transaction
     * ({@link #lockEndedAuction}). Paging by id means an auction that cannot be closed is not read again in the same run.
     */
    @Query(value = "select id from auctions where status = 'LIVE' and ends_at <= :now and id > :afterId " +
            "order by id limit :limit", nativeQuery = true)
    List<Long> findEndedAuctionIds(@Param("now") Instant now, @Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * Locks one auction, but only if it is still LIVE and its end time has passed. {@code SKIP LOCKED}: if a bid or
     * another job holds the row, this returns empty at once instead of waiting. A bid that extended the auction
     * (anti-sniping) moved {@code ends_at} past {@code now}, so it is not returned either. Call it inside a transaction:
     * the lock lasts until it ends.
     */
    @Query(value = "select * from auctions where id = :id and status = 'LIVE' and ends_at <= :now for update skip locked",
            nativeQuery = true)
    Optional<Auction> lockEndedAuction(@Param("id") long id, @Param("now") Instant now);

    /**
     * {@code expected} -&gt; {@code next}, only if the auction is still in the {@code expected} status. The caller MUST
     * check the returned row count (1 = changed). A bulk update does not run {@code @UpdateTimestamp}, so
     * {@code updated_at} is set here.
     */
    @Modifying
    @Query("update Auction a set a.status = :next, a.version = a.version + 1, a.updatedAt = :now " +
            "where a.id = :auctionId and a.status = :expected")
    int updateAuctionStatus(@Param("auctionId") Long auctionId, @Param("expected") AuctionStatus expected,
                            @Param("next") AuctionStatus next, @Param("now") Instant now);



    Optional<Auction> findByPublicId(UUID publicId);

    /**
     * -&gt; CANCELLED, only while nobody has bid: SCHEDULED, or LIVE with no bid. One statement, so a bid and a cancel
     * cannot both win ({@link #placeBid} needs LIVE, this needs a bid count of 0, and the second one re-checks the row).
     * The caller MUST check the row count and give the ticket back in the same transaction.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Auction a set a.status = AuctionStatus.CANCELLED, a.version = a.version + 1, a.updatedAt = :now "
            + "where a.id = :auctionId and (a.status = AuctionStatus.SCHEDULED "
            + "or (a.status = AuctionStatus.LIVE and a.bidCount = 0))")
    int cancelWithoutBids(@Param("auctionId") Long auctionId, @Param("now") Instant now);

    /**
     * Takes a bid in ONE statement: the auction must be LIVE and not over, and {@code amount} must reach the start price
     * (first bid) or the current price plus the minimum increment. The price, the leader and the bid count change together,
     * and the end time moves forward by {@code extension_sec} when the bid lands inside the anti-sniping window (the last
     * {@code anti_snipe_window_sec} seconds). The statement locks the row, so a second bid waits and then re-checks the
     * conditions against the new price: it can lose, it can never overwrite.
     * <p>
     * The caller MUST check the row count: 0 means the bid was refused (over, not LIVE, or too low). The persistence
     * context is cleared because this bypasses it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update auctions
               set current_price     = :amount,
                   leading_bidder_id = :bidderId,
                   bid_count         = bid_count + 1,
                   ends_at           = case
                                         when ends_at - interval '1 second' * anti_snipe_window_sec <= :now
                                         then ends_at + interval '1 second' * extension_sec
                                         else ends_at
                                       end,
                   version           = version + 1,
                   updated_at        = :now
             where id = :auctionId
               and status = 'LIVE'
               and ends_at > :now
               and :amount >= coalesce(current_price + min_increment, start_price)
            """, nativeQuery = true)
    int placeBid(@Param("auctionId") Long auctionId, @Param("bidderId") Long bidderId,
                 @Param("amount") long amount, @Param("now") Instant now);


    @Modifying
    @Query("update Auction a set a.status = AuctionStatus.SETTLED, a.updatedAt = :now, a.version = a.version + 1 " +
            "where a.status = AuctionStatus.AWAITING_PAYMENT and " +
            "a.id = (select o.auction.id from AuctionOffer o where o.orderId = :orderId)")
    int markAuctionSettled(@Param("orderId") Long orderId, @Param("now") Instant now);
}
