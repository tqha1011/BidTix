package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.domain.Bid;
import com.bidtix.auction.service.AuctionSettlementService;
import com.bidtix.auction.service.AuctionSettlementService.Returned;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Closes auctions whose end time has passed. {@code ends_at} is already the real end: a bid inside the anti-sniping
 * window moved it forward, so nothing is added to it here.
 * <ul>
 *   <li>A winner (a price, and at least the reserve if there is one): the auction becomes AWAITING_PAYMENT and the
 *       winner gets an AUCTION order and a rank-1 offer that share one deadline. The ticket stays out of sale.</li>
 *   <li>No bid, or a price under the reserve: the auction becomes FAILED and its ticket goes back on sale (the seat
 *       AVAILABLE, or one more ticket on the zone counter).</li>
 * </ul>
 * The job reads the ids of ended auctions, then handles each in its own transaction: lock it
 * ({@code FOR UPDATE SKIP LOCKED}), change its status with a conditional UPDATE (row count checked) and write the
 * order, the offer or the returned stock. The writes differ per auction, so there is nothing to share across a batch,
 * and one auction that cannot be closed rolls back only itself. Several app instances can run it at once.
 * <p>
 * After the commit of a FAILED auction the booking gate flags of the ticket that came back are cleared (see
 * {@link BookingGateway}); clearing earlier would let a request set the flag again while the ticket is still taken.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionClosingJob {
    static final int BATCH_SIZE = 100;
    static final int OFFER_RANK = 1;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final AuctionSettlementService settlement;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** What closing one auction produced; {@code returned} is set only when a ticket went back on sale. */
    private record Closed(@Nullable Returned returned) {
    }

    // fixedDelay: the next run starts one minute after the previous one ended, so runs of one instance never overlap
    @Scheduled(fixedDelay = 60_000)
    public int closeAuction() {
        int closed = 0;
        int failed = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = auctionRepository.findEndedAuctionIds(clock.instant(), lastId, BATCH_SIZE);
            for (Long auctionId : batch) {
                try {
                    Closed result = transactionTemplate.execute(status -> close(auctionId));
                    if (result != null) {
                        settlement.clearGate(result.returned());
                        closed++;
                    }
                } catch (RuntimeException e) {
                    // This auction rolled back as a whole, nothing is half done. The others go on; it is retried next run.
                    failed++;
                    log.error("Could not close auction {}", auctionId, e);
                }
            }
            if (!batch.isEmpty()) {
                lastId = batch.getLast();   // the next batch starts after it, so a failing auction is not read again
            }
        } while (batch.size() == BATCH_SIZE);

        if (closed > 0 || failed > 0) {
            log.info("Closed {} auctions, {} failed", closed, failed);
        }
        return closed;
    }

    /** One auction, one transaction. Returns null when it is not ours to close (locked elsewhere, extended by a late bid, already closed). */
    private Closed close(Long auctionId) {
        Instant now = clock.instant();
        Auction auction = auctionRepository.lockEndedAuction(auctionId, now).orElse(null);
        if (auction == null) {
            return null;
        }

        Long price = auction.getCurrentPrice();
        Long reserve = auction.getReservePrice();
        boolean hasWinner = price != null && (reserve == null || price >= reserve);
        if (!hasWinner) {
            return new Closed(settlement.fail(auction, AuctionStatus.LIVE, now));
        }

        Bid winningBid = bidRepository.findByAuctionIdAndBidderIdAndAmount(
                        auction.getId(), auction.getLeadingBidderId(), price)
                .orElseThrow(() -> new IllegalStateException("Auction " + auction.getId()
                        + " has a leader and a price but no bid with that amount"));
        settlement.changeStatus(auction, AuctionStatus.LIVE, AuctionStatus.AWAITING_PAYMENT, now);
        settlement.openOffer(auction, winningBid, OFFER_RANK, now);
        return new Closed(null);
    }
}
