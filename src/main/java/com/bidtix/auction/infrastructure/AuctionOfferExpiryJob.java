package com.bidtix.auction.infrastructure;

import com.bidtix.config.metrics.Metrics.Job;
import com.bidtix.config.metrics.Metrics;
import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionOffer;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.domain.Bid;
import com.bidtix.auction.service.AuctionSettlementService;
import com.bidtix.auction.service.AuctionSettlementService.Returned;
import com.bidtix.booking.domain.OrderStatus;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.payment.service.ConfirmationGrace;
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
 * Deals with offers that were not paid before their deadline. For each such offer, in its own transaction:
 * <ol>
 *   <li>lock the offer ({@code FOR UPDATE SKIP LOCKED}) and cancel its unpaid AUCTION order with a conditional UPDATE;</li>
 *   <li>if that UPDATE changed nothing, the order was paid at the last moment: the offer is ACCEPTED and the auction
 *       SETTLED, and nothing passes on (the payment is the same UPDATE on the same row, so exactly one side wins);</li>
 *   <li>otherwise the offer is EXPIRED and the right to buy passes to the highest bidder who has not had an offer, at
 *       their own highest bid, as the next rank, if that bid reaches the reserve;</li>
 *   <li>with nobody left the auction FAILS and its ticket goes back on sale (see {@link AuctionSettlementService}).</li>
 * </ol>
 * An offer whose order has a payment still waiting for the provider's callback is left open for a grace period after its
 * deadline (see {@link ConfirmationGrace}); the callback accepts it only if the customer paid before the deadline.
 * The old offer is EXPIRED before the new one is inserted, because at most one offer per auction may be PENDING.
 * The orders of AUCTION offers are not touched by {@code OrderCancellationJob}, so this job is the only thing that
 * cancels them. After the commit of a FAILED auction the booking gate flags of the ticket that came back are cleared.
 * Several app instances can run it at once; one offer that cannot be handled rolls back only itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionOfferExpiryJob {
    static final int BATCH_SIZE = 100;

    private final AuctionOfferRepository auctionOfferRepository;
    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;
    private final BidRepository bidRepository;
    private final AuctionSettlementService settlement;
    private final TransactionTemplate transactionTemplate;
    private final ConfirmationGrace confirmationGrace;
    private final Clock clock;
    private final Metrics metrics;

    /** What handling one offer produced; {@code returned} is set only when a ticket went back on sale. */
    private record Handled(@Nullable Returned returned) {
    }

    // fixedDelay: the next run starts one minute after the previous one ended, so runs of one instance never overlap
    @Scheduled(fixedDelay = 60_000)
    public int expireOffers() {
        int handled = 0;
        int failed = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = auctionOfferRepository.findExpiredPendingOfferIds(clock.instant(), lastId, BATCH_SIZE);
            for (Long offerId : batch) {
                try {
                    Handled result = transactionTemplate.execute(status -> handle(offerId));
                    if (result != null) {
                        settlement.clearGate(result.returned());
                        handled++;
                    }
                } catch (RuntimeException e) {
                    // This offer rolled back as a whole, nothing is half done. The others go on; it is retried next run.
                    failed++;
                    metrics.jobItemFailed(Job.OFFER_EXPIRY);
                    log.error("Could not handle expired offer {}", offerId, e);
                }
            }
            if (!batch.isEmpty()) {
                lastId = batch.getLast();   // the next batch starts after it, so a failing offer is not read again
            }
        } while (batch.size() == BATCH_SIZE);

        if (handled > 0 || failed > 0) {
            log.info("Handled {} expired offers, {} failed", handled, failed);
        }
        return handled;
    }

    /** One offer, one transaction. Returns null when it is not ours (locked elsewhere, paid or handled meanwhile). */
    private Handled handle(Long offerId) {
        Instant now = clock.instant();
        AuctionOffer offer = auctionOfferRepository.lockExpiredPendingOffer(offerId, now).orElse(null);
        if (offer == null) {
            return null;
        }
        // a payment may have been made in time and not be confirmed yet: leave the offer open for the callback
        if (offer.getExpiresAt().isAfter(confirmationGrace.cutoff(now))
                && !confirmationGrace.ordersAwaitingConfirmation(List.of(offer.getOrderId())).isEmpty()) {
            return null;
        }
        Auction auction = auctionRepository.findById(offer.getAuction().getId())
                .orElseThrow(() -> new IllegalStateException("Auction of offer " + offerId + " not found"));

        if (orderRepository.cancelAuctionOrder(offer.getOrderId(), now) == 0) {
            return paidInTime(offer, auction, now);
        }
        if (auctionOfferRepository.markExpired(offerId, now) != 1) {
            throw new IllegalStateException("Locked offer " + offerId + " but could not expire it");
        }

        Long reserve = auction.getReservePrice();
        Bid next = bidRepository.findBestBidWithoutOffer(auction.getId())
                .filter(bid -> reserve == null || bid.getAmount() >= reserve)
                .orElse(null);
        if (next != null) {
            settlement.openOffer(auction, next, offer.getOfferRank() + 1, now);
            return new Handled(null);
        }
        return new Handled(settlement.fail(auction, AuctionStatus.AWAITING_PAYMENT, now));
    }

    /** The order could not be cancelled. If it was paid, the offer is accepted and the auction settled; anything else is unexpected. */
    private Handled paidInTime(AuctionOffer offer, Auction auction, Instant now) {
        OrderStatus status = orderRepository.findStatusById(offer.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order " + offer.getOrderId() + " of offer "
                        + offer.getId() + " not found"));
        if (status != OrderStatus.PAID) {
            throw new IllegalStateException("Order " + offer.getOrderId() + " of offer " + offer.getId()
                    + " is " + status + ", expected PENDING_PAYMENT or PAID");
        }
        if (auctionOfferRepository.markAccepted(offer.getId(), now) != 1) {
            throw new IllegalStateException("Locked offer " + offer.getId() + " but could not accept it");
        }
        settlement.changeStatus(auction, AuctionStatus.AWAITING_PAYMENT, AuctionStatus.SETTLED, now);
        return new Handled(null);
    }
}
