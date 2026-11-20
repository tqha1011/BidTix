package com.bidtix.auction.infrastructure;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionOffer;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.domain.Bid;
import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.BookingProperties;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.common.utils.GenerateOrderCode;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
    static final int QUANTITY_TO_RELEASE = 1;
    static final int OFFER_RANK = 1;

    private final AuctionRepository auctionRepository;
    private final AuctionOfferRepository auctionOfferRepository;
    private final OrderRepository orderRepository;
    private final TicketZoneRepository ticketZoneRepository;
    private final BidRepository bidRepository;
    private final SeatRepository seatRepository;
    private final BookingProperties properties;
    private final BookingGateway bookingGateway;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** The gate flags that are out of date after a failed auction gave its ticket back. */
    private record Returned(UUID zonePublicId, @Nullable UUID seatPublicId) {
    }

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
                        clearGate(result.returned());
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
        return hasWinner ? award(auction, price, now) : fail(auction, now);
    }

    private Closed award(Auction auction, long price, Instant now) {
        Bid winningBid = bidRepository.findByAuctionIdAndBidderIdAndAmount(
                        auction.getId(), auction.getLeadingBidderId(), price)
                .orElseThrow(() -> new IllegalStateException("Auction " + auction.getId()
                        + " has a leader and a price but no bid with that amount"));
        changeStatus(auction, AuctionStatus.AWAITING_PAYMENT, now);

        // one deadline for the order and the offer: a gap between them would let the ticket be paid twice
        Instant deadline = now.plus(properties.orderExpirationMinutes(), ChronoUnit.MINUTES);
        Order order = new Order(winningBid.getBidderId(), auction.getEventId(), GenerateOrderCode.generate(),
                OrderSource.AUCTION, deadline);
        order.addItem(auction.getZoneId(), auction.getSeatId(), QUANTITY_TO_RELEASE, price);
        orderRepository.saveAndFlush(order);

        auctionOfferRepository.saveAndFlush(new AuctionOffer(auction, winningBid, OFFER_RANK, order.getId(), deadline));
        return new Closed(null);
    }

    private Closed fail(Auction auction, Instant now) {
        changeStatus(auction, AuctionStatus.FAILED, now);
        Returned returned = release(auction);
        return new Closed(returned);
    }

    /** LIVE -> {@code status}. The row is locked by us, so a count other than 1 means the lock and the UPDATE disagree. */
    private void changeStatus(Auction auction, AuctionStatus status, Instant now) {
        if (auctionRepository.updateAuctionStatus(auction.getId(), status, now) != 1) {
            throw new IllegalStateException("Locked auction " + auction.getId() + " but could not move it to " + status);
        }
    }

    /** Puts the auctioned ticket back on sale and says which gate flags that makes out of date. */
    private Returned release(Auction auction) {
        UUID zonePublicId = ticketZoneRepository.findPublicIdByid(auction.getZoneId())
                .orElseThrow(() -> new IllegalStateException("Zone " + auction.getZoneId() + " of auction "
                        + auction.getId() + " not found"));

        if (auction.getSeatId() != null) {
            Seat seat = seatRepository.findById(auction.getSeatId())
                    .orElseThrow(() -> new IllegalStateException("Seat " + auction.getSeatId() + " not found"));
            if (seatRepository.releaseSeatFromAuction(auction.getSeatId()) != 1) {
                throw new IllegalStateException("Seat " + auction.getSeatId() + " was not reserved for the auction");
            }
            return new Returned(zonePublicId, seat.getPublicId());
        }

        if (ticketZoneRepository.refundQuantity(auction.getZoneId(), QUANTITY_TO_RELEASE) != 1) {
            throw new IllegalStateException("Zone " + auction.getZoneId() + " not found to give the ticket back to");
        }
        return new Returned(zonePublicId, null);
    }

    /** Only after the commit: the ticket is really back, so the "sold out" memory is wrong from now on. */
    private void clearGate(@Nullable Returned returned) {
        if (returned == null) {
            return;
        }
        if (returned.seatPublicId() != null) {
            bookingGateway.clearSeatFlags(returned.zonePublicId(), List.of(returned.seatPublicId()));
        } else {
            bookingGateway.clearQuantityRejected(returned.zonePublicId());
        }
    }
}
