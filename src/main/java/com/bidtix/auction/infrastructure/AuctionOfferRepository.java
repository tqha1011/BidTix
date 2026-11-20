package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.AuctionOffer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuctionOfferRepository extends JpaRepository<AuctionOffer, Long> {
}
