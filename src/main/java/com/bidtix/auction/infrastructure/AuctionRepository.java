package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Auction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

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
}
