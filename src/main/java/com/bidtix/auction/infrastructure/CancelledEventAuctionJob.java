package com.bidtix.auction.infrastructure;

import com.bidtix.booking.infrastructure.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Cancels the open auctions of CANCELLED events (SCHEDULED, LIVE or AWAITING_PAYMENT), with or without bids. An auction
 * waiting for payment also has its offer closed and its unpaid AUCTION order cancelled. No ticket goes back on sale:
 * nobody can buy for a cancelled event. Paid auction orders are refunded by the booking module.
 * <p>
 * The job looks for state, not for a message, so an auction that another job moved on meanwhile (opened, closed, offered
 * to the next bidder) is picked up by a later run.
 * <p>
 * Skeleton: it does nothing yet.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CancelledEventAuctionJob {
    static final int BATCH_SIZE = 100;

    private final AuctionRepository auctionRepository;
    private final AuctionOfferRepository auctionOfferRepository;
    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 5_000)
    public void cancelAuctionsOfCancelledEvents() {
        // TODO 1. read a batch of open auction ids whose event is CANCELLED (paged by id)
        // TODO 2. one transaction per auction, locked with FOR UPDATE SKIP LOCKED; change statuses with conditional
        //         UPDATEs and check the row counts
        // TODO 3. AWAITING_PAYMENT: close the offer FIRST, then cancel its order (the same lock order as a payment, so
        //         the two cannot deadlock). 0 rows means it was paid or expired meanwhile: roll back, retry next run
    }
}
