package com.bidtix.auction.infrastructure;

import com.bidtix.config.metrics.Metrics.Job;
import com.bidtix.config.metrics.Metrics;
import com.bidtix.auction.domain.AuctionOffer;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.service.AuctionSettlementService;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.event.infrastructure.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Calls off the open auctions of CANCELLED events, with or without bids:
 * <ul>
 *   <li>SCHEDULED or LIVE: CANCELLED, a batch in one statement.</li>
 *   <li>AWAITING_PAYMENT: one transaction each. The offer is locked FIRST, as a payment and
 *       {@link AuctionOfferExpiryJob} take it first, so they cannot deadlock and a payment waits; then the unpaid AUCTION
 *       order is cancelled, the offer expired and the auction cancelled, every row count checked.</li>
 * </ul>
 * No ticket goes back on sale: nobody can buy for a cancelled event. Paid auction orders are not touched here: the
 * booking module refunds them, and their auction stays SETTLED.
 * <p>
 * The job looks for state, not for a message, so an auction another job moved on meanwhile (opened, closed, offered to
 * the next bidder) is picked up by a later run. Once an event has no open auction left and was cancelled more than
 * {@link #SETTLE_WINDOW} ago, it is marked ({@code auctions_cleaned_at}) and not looked at again. The window is there
 * for a request that was already running when the event was cancelled and commits afterwards.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelledEventAuctionJob {
    static final int BATCH_SIZE = 100;
    static final Duration SETTLE_WINDOW = Duration.ofMinutes(5);

    private final AuctionRepository auctionRepository;
    private final AuctionOfferRepository auctionOfferRepository;
    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final Metrics metrics;
    private final AuctionSettlementService settlement;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 5_000)
    public void cancelAuctionsOfCancelledEvents() {
        cancelOpenAuctions();
        cancelAuctionsAwaitingPayment();
        markCleanedEvents();
    }

    private void cancelOpenAuctions() {
        int cancelled = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactionTemplate.execute(
                    status -> auctionRepository.cancelOpenAuctionsOfCancelledEvents(clock.instant(), BATCH_SIZE)));
            cancelled += batch;
        } while (batch == BATCH_SIZE);

        if (cancelled > 0) {
            log.info("Cancelled {} open auctions of cancelled events", cancelled);
        }
    }

    private void cancelAuctionsAwaitingPayment() {
        int cancelled = 0;
        long lastId = 0;
        List<Long> batch;
        do {
            batch = auctionOfferRepository.findPendingOfferIdsOfCancelledEvents(lastId, BATCH_SIZE);
            for (Long offerId : batch) {
                try {
                    if (Boolean.TRUE.equals(transactionTemplate.execute(status -> cancelAwaitingPayment(offerId)))) {
                        cancelled++;
                    }
                } catch (RuntimeException e) {
                    metrics.jobItemFailed(Job.CANCELLED_EVENT_AUCTIONS);
                    log.error("Could not cancel the auction of offer {} of a cancelled event; it is retried on the next run",
                            offerId, e);
                }
            }
            if (!batch.isEmpty()) {
                lastId = batch.getLast();   // the next batch starts after it, so a failing offer is not read again
            }
        } while (batch.size() == BATCH_SIZE);

        if (cancelled > 0) {
            log.info("Cancelled {} auctions of cancelled events that were awaiting payment", cancelled);
        }
    }

    /** One auction, one transaction. False when the offer is not ours: held by another transaction, or not open any more. */
    private boolean cancelAwaitingPayment(long offerId) {
        AuctionOffer offer = auctionOfferRepository.lockPendingOffer(offerId).orElse(null);
        if (offer == null) {
            return false;
        }
        Instant now = clock.instant();
        if (orderRepository.cancelAuctionOrder(offer.getOrderId(), now) != 1) {
            // an open offer whose order is not unpaid cannot happen while we hold the offer: a person has to look at it
            throw new IllegalStateException("Order " + offer.getOrderId() + " of open offer " + offerId
                    + " is not waiting for payment");
        }
        if (auctionOfferRepository.markExpired(offerId, now) != 1) {
            throw new IllegalStateException("Locked offer " + offerId + " but could not expire it");
        }
        settlement.changeStatus(offer.getAuction(), AuctionStatus.AWAITING_PAYMENT, AuctionStatus.CANCELLED, now);
        return true;
    }

    private void markCleanedEvents() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = clock.instant();
            List<Long> done = auctionRepository.findCancelledEventsWithoutOpenAuctions(now.minus(SETTLE_WINDOW));
            if (!done.isEmpty()) {
                eventRepository.markAuctionsCleaned(done, now);
            }
        });
    }
}
