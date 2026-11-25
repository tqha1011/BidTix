package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Bid;
import com.bidtix.auction.dtos.BidHistoryResponse;
import org.springframework.data.domain.Page;
import com.bidtix.auction.domain.LeaderboardEntry;
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

    /**
     * The bidders of one auction, highest bid first, each counted once with their highest bid. {@code Pageable} sets how
     * many ({@code PageRequest.of(0, 10)} is the top 10). No two bids of an auction have the same amount
     * ({@code uq_bids_auction_amount}), so the order never has ties. Only ids and amounts come back: names are looked up
     * separately for the lines that are shown (see {@code UserRepository.findByIdIn}), so a cached leaderboard never
     * holds personal data. This reads every bid of the auction, so it is the source to rebuild a cached leaderboard and
     * the fallback when the cache is down, not something to call on every page view of a busy auction.
     */
    @Query("""
            select b.bidderId, max(b.amount)
              from Bid b
             where b.auction.publicId = :auctionPublicId
             group by b.bidderId
             order by max(b.amount) desc
            """)
    List<LeaderboardEntry> findTopBids(@Param("auctionPublicId") UUID auctionPublicId, Pageable pageable);

    /**
     * The accepted bids of one auction, highest (so newest) first, with the bidder's name: one query. A read model that
     * joins the user module by id, like the other list views.
     */
    @Query(value = """
            select new com.bidtix.auction.dtos.BidHistoryResponse(u.fullName, b.amount, b.createdAt)
              from Bid b
              join User u on u.id = b.bidderId
             where b.auction.publicId = :auctionPublicId
             order by b.amount desc
            """,
            countQuery = "select count(b) from Bid b where b.auction.publicId = :auctionPublicId")
    Page<BidHistoryResponse> findHistory(@Param("auctionPublicId") UUID auctionPublicId, Pageable pageable);
}
