package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Bid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    /** One line of an auction's leaderboard: a bidder and their highest bid in it. */
    record TopBidder(String fullName, Long amount) {
    }

    /**
     * The bidders of one auction, highest bid first, each counted once with their highest bid. {@code Pageable} sets how
     * many ({@code PageRequest.of(0, 10)} is the top 10). No two bids of an auction have the same amount
     * ({@code uq_bids_auction_amount}), so the order never has ties. The users table is joined by id because a bid keeps only
     * the bidder's id (the modules are not linked by JPA). This reads every bid of the auction, so it is the fallback and the
     * source to rebuild a cached leaderboard, not something to call on every page view of a busy auction.
     */
    @Query("""
            select u.fullName, max(b.amount)
              from Bid b join User u on u.id = b.bidderId
             where b.auction.publicId = :auctionPublicId
             group by u.id, u.fullName
             order by max(b.amount) desc
            """)
    List<TopBidder> findTopBidders(@Param("auctionPublicId") UUID auctionPublicId, Pageable pageable);
}
