package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionStatus;
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
}
