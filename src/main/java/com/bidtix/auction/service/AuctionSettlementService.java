package com.bidtix.auction.service;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionOffer;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.domain.Bid;
import com.bidtix.auction.infrastructure.AuctionOfferRepository;
import com.bidtix.auction.infrastructure.AuctionRepository;
import com.bidtix.booking.domain.Order;
import com.bidtix.booking.domain.OrderPaidEvent;
import com.bidtix.booking.domain.OrderSource;
import com.bidtix.booking.infrastructure.BookingGateway;
import com.bidtix.booking.infrastructure.BookingProperties;
import com.bidtix.booking.infrastructure.OrderRepository;
import com.bidtix.common.utils.GenerateOrderCode;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The two things the closing job and the offer expiry job both do to an auction: give the right to buy to a bidder
 * ({@link #openOffer}) and fail the auction, putting its ticket back on sale ({@link #fail}).
 * <p>
 * The methods that write ({@code changeStatus}, {@code openOffer}, {@code fail}) have no transaction of their own: the
 * caller (a job) opens one transaction per auction or offer and calls them inside it, so the status change and its
 * order, offer or returned stock commit or roll back together. {@link #clearGate} is called AFTER that commit.
 */
@Service
@RequiredArgsConstructor
public class AuctionSettlementService {
    /** An auction sells exactly one ticket. */
    static final int QUANTITY = 1;

    private final AuctionRepository auctionRepository;
    private final AuctionOfferRepository auctionOfferRepository;
    private final OrderRepository orderRepository;
    private final TicketZoneRepository ticketZoneRepository;
    private final SeatRepository seatRepository;
    private final BookingProperties properties;
    private final BookingGateway bookingGateway;

    /** The booking gate flags that are out of date after a failed auction gave its ticket back. */
    public record Returned(UUID zonePublicId, @Nullable UUID seatPublicId) {
    }

    /** {@code expected} -> {@code next}. The row is locked by the caller, so a count other than 1 means the lock and the UPDATE disagree. */
    public void changeStatus(Auction auction, AuctionStatus expected, AuctionStatus next, Instant now) {
        if (auctionRepository.updateAuctionStatus(auction.getId(), expected, next, now) != 1) {
            throw new IllegalStateException("Locked auction " + auction.getId() + " but could not move it from "
                    + expected + " to " + next);
        }
    }

    /**
     * Gives {@code bid}'s bidder the right to buy the auctioned ticket at the bid price: an AUCTION order and an offer
     * of the given rank that share one deadline. One deadline, because a gap between them would let the ticket be
     * paid twice.
     */
    public void openOffer(Auction auction, Bid bid, int rank, Instant now) {
        Instant deadline = now.plus(properties.orderExpirationMinutes(), ChronoUnit.MINUTES);
        Order order = new Order(bid.getBidderId(), auction.getEventId(), GenerateOrderCode.generate(),
                OrderSource.AUCTION, deadline,null);
        order.addItem(auction.getZoneId(), auction.getSeatId(), QUANTITY, bid.getAmount());
        orderRepository.saveAndFlush(order);

        auctionOfferRepository.saveAndFlush(new AuctionOffer(auction, bid, rank, order.getId(), deadline));
    }

    /**
     * {@code expected} -> FAILED and the auctioned ticket goes back on sale (the seat AVAILABLE, or one more ticket on
     * the zone counter). Returns which gate flags that makes out of date, for {@link #clearGate} after the commit.
     */
    public Returned fail(Auction auction, AuctionStatus expected, Instant now) {
        changeStatus(auction, expected, AuctionStatus.FAILED, now);
        return returnTicket(auction);
    }

    /**
     * Puts the auctioned ticket back on sale. Only for the transaction that has just ended the auction without a sale,
     * so it happens once. Returns which gate flags that makes out of date, for {@link #clearGate} after the commit.
     */
    public Returned returnTicket(Auction auction) {
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

        if (ticketZoneRepository.refundQuantity(auction.getZoneId(), QUANTITY) != 1) {
            throw new IllegalStateException("Zone " + auction.getZoneId() + " not found to give the ticket back to");
        }
        return new Returned(zonePublicId, null);
    }

    /**
     * The winner (or a second-chance bidder) paid: accept the offer of this order and settle the auction, with one conditional
     * UPDATE each, found by the order id. Runs inside the transaction that marked the order PAID (a plain, synchronous
     * listener, not an after-commit one), so the order, the offer and the auction commit or roll back together, and a
     * failure surfaces to the code that published the event.
     * <p>
     * Only auction orders matter here; any other order is ignored. If the offer cannot be accepted (it is no longer PENDING
     * or its deadline has passed), the payment cannot stand: {@code ORDER_NOT_PAYABLE}, which rolls everything back and is
     * handled like any other "pay too late" (the money is refunded, the expiry job then cancels the order). An auction that
     * is not awaiting payment although its offer was pending is not something that can happen, so that is a plain error.
     */
    @EventListener
    public void markAuctionOfferCompleteAndSettle(OrderPaidEvent event) {
        if (event.source() != OrderSource.AUCTION) {
            return;
        }
        if (auctionOfferRepository.markOfferAcceptedByOrderId(event.orderId(), event.paidAt()) != 1) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "The offer for this order is no longer open");
        }
        if (auctionRepository.markAuctionSettled(event.orderId(), event.paidAt()) != 1) {
            throw new IllegalStateException("Offer of order " + event.orderId() + " was accepted, but its auction was not "
                    + "AWAITING_PAYMENT to settle");
        }
    }

    /** Only after the commit: the ticket is really back, so the "sold out" memory is wrong from now on. */
    public void clearGate(@Nullable Returned returned) {
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
