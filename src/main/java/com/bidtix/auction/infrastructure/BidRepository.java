package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Bid;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface BidRepository extends CrudRepository<Bid, Long> {

    Optional<Bid> findByAuctionIdAndBidderIdAndAmount(Long auctionId, Long bidderId, long amount);
}
