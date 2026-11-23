package com.bidtix.auction.service;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.domain.Bid;
import com.bidtix.auction.dtos.PlaceBidRequest;
import com.bidtix.auction.dtos.PlaceBidResponse;
import com.bidtix.auction.infrastructure.AuctionRepository;
import com.bidtix.auction.infrastructure.BidRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidService {
    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Places a bid. Step 1 refuses an obviously bad bid from the auction row alone, before the bidder is read and before
     * any write. Step 2 is the real decision: ONE conditional UPDATE ({@link AuctionRepository#placeBid}) that checks the
     * status, the end time and the minimum price again and moves the price, the leader and the end time (anti-sniping)
     * together. Its row count decides: 1 means accepted and the bid row is stored in the same transaction; 0 means the
     * auction changed after step 1 (someone bid higher, or it just ended).
     * <p>
     * Step 1 can only refuse, never accept, and it is safe on a slightly old row: an auction that is over or no longer
     * LIVE never comes back, and the price never goes down, so a bid that is too low for the old row is too low for the
     * real one. A bid that passes step 1 may still lose in step 2.
     */
    @Transactional
    public PlaceBidResponse placeBid(PlaceBidRequest request, UUID bidderPublicId) {
        Instant now = clock.instant();
        Auction auction = auctionRepository.findByPublicId(request.auctionPublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        long amount = request.amount();

        BusinessException refusal = refusalFor(auction, amount, now);
        if (refusal != null) {
            throw refusal;
        }

        User bidder = userRepository.findByPublicId(bidderPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        if (auctionRepository.placeBid(auction.getId(), bidder.getId(), amount, now) != 1) {
            // lost a race between the read and the UPDATE: read the new state to say why
            Auction current = auctionRepository.findById(auction.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
            BusinessException why = refusalFor(current, amount, now);
            throw why != null ? why : new BusinessException(ErrorCode.INVALID_MONEY_BID);
        }
        bidRepository.save(new Bid(auctionRepository.getReferenceById(auction.getId()), bidder.getId(), amount));
        return PlaceBidResponse.create(bidder.getPublicId(), auction.getPublicId(), amount, bidder.getFullName());
    }

    /**
     * The error this bid deserves against this auction row, or {@code null} when the row does not rule it out.
     * The same rules as the WHERE of {@code placeBid}: LIVE, not over, and the amount reaches the start price (first
     * bid) or the current price plus the minimum increment.
     */
    private static BusinessException refusalFor(Auction auction, long amount, Instant now) {
        if (auction.getStatus() != AuctionStatus.LIVE || !auction.getEndsAt().isAfter(now)) {
            return new BusinessException(ErrorCode.BID_NO_LONGER_AVAILABLE);
        }
        long minimum = auction.getCurrentPrice() == null
                ? auction.getStartPrice()
                : auction.getCurrentPrice() + auction.getMinIncrement();
        if (amount < minimum) {
            return new BusinessException(ErrorCode.INVALID_MONEY_BID);
        }
        return null;
    }
}
