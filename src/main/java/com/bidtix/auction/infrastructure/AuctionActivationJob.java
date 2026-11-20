package com.bidtix.auction.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;

/**
 * Opens auctions: SCHEDULED -&gt; LIVE when {@code starts_at} has passed. Bids are accepted only on a LIVE auction, so
 * an auction opens at most one run late. The run is short (every 5 seconds) because of that.
 * <p>
 * Each batch of up to {@value #BATCH_SIZE} auctions is one conditional UPDATE in its own transaction (see
 * {@link AuctionRepository#openDueAuctions}); there is no stock to move and nothing to clear in Redis, so unlike the
 * order cancellation job there is nothing else to keep consistent. Several app instances can run it at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionActivationJob {
    static final int BATCH_SIZE = 100;

    private final AuctionRepository auctionRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // fixedDelay: the next run starts 5 seconds after the previous one ended, so runs of one instance never overlap
    @Scheduled(fixedDelay = 5_000)
    public int activateDueAuctions() {
        int opened = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactionTemplate.execute(
                    status -> auctionRepository.openDueAuctions(clock.instant(), BATCH_SIZE)));
            opened += batch;
        } while (batch == BATCH_SIZE);

        if (opened > 0) {
            log.info("Opened {} auctions", opened);
        }
        return opened;
    }
}
