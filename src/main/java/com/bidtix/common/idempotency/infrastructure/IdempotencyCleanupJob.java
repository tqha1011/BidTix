package com.bidtix.common.idempotency.infrastructure;

import com.bidtix.common.idempotency.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the idempotency keys whose time is up. Nothing else removes a key that is never used again, so without this
 * the table only grows. Each batch is a short transaction of its own and skips rows another transaction holds, so
 * several instances can run it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyCleanupJob {

    static final int BATCH_SIZE = 1000;
    /** A run ends after this many batches, because the scheduled jobs share one thread; the next run goes on. */
    static final int MAX_BATCHES = 100;

    private final IdempotencyService service;

    // every minute by default; "-" turns it off (the tests do)
    @Scheduled(cron = "${bidtix.idempotency.cleanup-cron:45 * * * * *}")
    public void deleteExpiredKeys() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = service.deleteExpiredBatch(BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Deleted {} expired idempotency keys", total);
        }
    }
}
