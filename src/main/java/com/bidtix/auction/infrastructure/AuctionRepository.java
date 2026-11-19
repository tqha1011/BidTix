package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Auction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionRepository extends JpaRepository<Auction, Long> {
}
