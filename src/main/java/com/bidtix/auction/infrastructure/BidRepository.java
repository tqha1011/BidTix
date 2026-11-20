package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Bid;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BidRepository extends CrudRepository<Bid, Long> {

    Optional<Bid> findByAuctionIdAndBidderIdAndAmount(Long auctionId, Long bidderId, long amount);

    /**
     * The highest bid among bidders who have never had an offer in this auction. A bidder who bid several times counts
     * once: their highest bid is the highest of all their bids, so it is the first row when ordered by amount.
     */
    @Query(value = "select * from bids b where b.auction_id = :auctionId and b.bidder_id not in " +
            "(select user_id from auction_offers where auction_id = :auctionId) order by b.amount desc limit 1",
            nativeQuery = true)
    Optional<Bid> findBestBidWithoutOffer(@Param("auctionId") Long auctionId);
}
