package com.bidtix.auction.service;

import com.bidtix.auction.domain.Auction;
import com.bidtix.auction.domain.AuctionStatus;
import com.bidtix.auction.dtos.AuctionResponse;
import com.bidtix.auction.dtos.BidHistoryResponse;
import com.bidtix.auction.dtos.CreateAuctionRequest;
import com.bidtix.auction.infrastructure.AuctionProperties;
import com.bidtix.auction.infrastructure.AuctionRepository;
import com.bidtix.auction.infrastructure.BidRepository;
import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.domain.Seat;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final List<AuctionStatus> OPEN_STATUSES = List.of(AuctionStatus.SCHEDULED, AuctionStatus.LIVE);
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ACTIVE_SEAT_INDEX = "ux_auctions_active_seat";

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final EventRepository eventRepository;
    private final TicketZoneRepository zoneRepository;
    private final SeatRepository seatRepository;
    private final AuctionProperties properties;
    private final Clock clock;
    // Known exception to the module rules (same as auth): the user module has no service yet.
    private final UserRepository userRepository;
    // The event module has no service that hands out events, seats and zones yet, so this reads its repositories
    // directly, like ZoneService and BookingService do.

    /**
     * Creates a SCHEDULED auction for one ticket and takes that ticket out of normal sale, in one transaction:
     * either both happen or neither does.
     * <ul>
     *   <li>SEATED zone: the seat goes AVAILABLE -&gt; RESERVED_FOR_AUCTION with one conditional UPDATE whose row count
     *       is checked, so a booking that holds the seat and this auction cannot both win.</li>
     *   <li>GENERAL_ADMISSION zone: one ticket is subtracted from the zone counter with the same conditional UPDATE the
     *       booking uses. It is the last write, because it locks the hot counter row.</li>
     * </ul>
     * Taking tickets out of sale never needs the booking gate flags cleared (only giving tickets back does).
     */
    @Transactional
    public AuctionResponse createAuction(UUID organizerPublicId, CreateAuctionRequest request) {
        User organizer = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        Event event = eventRepository.findByPublicId(request.eventPublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        if (!event.getOrganizerId().equals(organizer.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.EVENT_NOT_PUBLISHED);
        }
        validatePricesAndTimes(request, event);

        TicketZone zone = zoneRepository.findByPublicId(request.zonePublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
        // a zone of another event is "not found" for this event
        if (!zone.getEvent().getId().equals(event.getId())) {
            throw new BusinessException(ErrorCode.ZONE_NOTFOUND);
        }
        Seat seat = resolveSeat(zone, request);

        Auction auction = new Auction(event.getId(), zone.getId(), seat == null ? null : seat.getId(),
                request.startPrice(), request.minIncrement(), request.reservePrice(),
                request.startsAt(), request.endsAt(),
                request.antiSnipeWindowSec() != null ? request.antiSnipeWindowSec() : properties.antiSnipeWindowSec(),
                request.extensionSec() != null ? request.extensionSec() : properties.extensionSec());
        try {
            // flush now: a seat that is already in an active auction must surface here, as a clean error
            auctionRepository.saveAndFlush(auction);
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause().getMessage() != null
                    && e.getMostSpecificCause().getMessage().contains(ACTIVE_SEAT_INDEX)) {
                log.info("Seat {} is already in an active auction", request.seatPublicId());
                throw seatUnavailable(request.seatPublicId());
            }
            throw e;
        }

        if (seat != null) {
            if (seatRepository.reserveForAuction(seat.getId()) != 1) {
                // sold, held by an order or reserved by another auction between our read and this update
                throw seatUnavailable(seat.getPublicId());
            }
        } else if (zoneRepository.takeQuantity(zone.getId(), 1) == 0) {
            throw new BusinessException(ErrorCode.ZONE_SOLD_OUT, "No ticket left in this zone to auction");
        }

        return AuctionResponse.from(auction, event.getPublicId(), zone.getPublicId(), seat == null ? null : seat.getPublicId());
    }

    /**
     * A page of auctions, soonest ending first. {@code status} null means the open ones (SCHEDULED and LIVE);
     * {@code eventPublicId} null means every event. The event, zone and seat public ids are resolved with one query
     * each for the whole page, not one per row.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuctionResponse> listAuctions(AuctionStatus status, UUID eventPublicId, int page, int size) {
        List<AuctionStatus> statuses = status == null ? OPEN_STATUSES : List.of(status);
        // id is the tie-breaker, so two auctions that end together never swap between pages
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.by("endsAt", "id"));
        Page<Auction> auctions;
        if (eventPublicId == null) {
            auctions = auctionRepository.findByStatusIn(statuses, pageable);
        } else {
            Event event = eventRepository.findByPublicId(eventPublicId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
            auctions = auctionRepository.findByEventIdAndStatusIn(event.getId(), statuses, pageable);
        }

        Map<Long, UUID> eventIds = new HashMap<>();
        eventRepository.findAllById(auctions.map(Auction::getEventId).toSet())
                .forEach(e -> eventIds.put(e.getId(), e.getPublicId()));
        Map<Long, UUID> zoneIds = new HashMap<>();
        zoneRepository.findAllById(auctions.map(Auction::getZoneId).toSet())
                .forEach(z -> zoneIds.put(z.getId(), z.getPublicId()));
        Map<Long, UUID> seatIds = new HashMap<>();
        seatRepository.findAllById(auctions.stream().map(Auction::getSeatId).filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(s -> seatIds.put(s.getId(), s.getPublicId()));

        return PageResponse.from(auctions.map(a -> AuctionResponse.from(a, eventIds.get(a.getEventId()),
                zoneIds.get(a.getZoneId()), a.getSeatId() == null ? null : seatIds.get(a.getSeatId()))));
    }

    /** One auction, as the list shows it. */
    @Transactional(readOnly = true)
    public AuctionResponse getAuction(UUID auctionPublicId) {
        Auction auction = findAuction(auctionPublicId);
        UUID eventId = eventRepository.findById(auction.getEventId()).orElseThrow().getPublicId();
        UUID zoneId = zoneRepository.findById(auction.getZoneId()).orElseThrow().getPublicId();
        UUID seatId = auction.getSeatId() == null ? null
                : seatRepository.findById(auction.getSeatId()).orElseThrow().getPublicId();
        return AuctionResponse.from(auction, eventId, zoneId, seatId);
    }

    /** The accepted bids of one auction, highest first. Bad paging values are corrected, as in the auction list. */
    @Transactional(readOnly = true)
    public PageResponse<BidHistoryResponse> listBids(UUID auctionPublicId, int page, int size) {
        findAuction(auctionPublicId);   // an unknown auction is not an empty history
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(bidRepository.findHistory(auctionPublicId, pageable));
    }

    private Auction findAuction(UUID auctionPublicId) {
        return auctionRepository.findByPublicId(auctionPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
    }

    /** SEATED zones need a seat of that zone; GENERAL_ADMISSION zones take none. Returns the seat, or null for a standing ticket. */
    private Seat resolveSeat(TicketZone zone, CreateAuctionRequest request) {
        if (zone.getZoneType() == ZoneType.GENERAL_ADMISSION) {
            if (request.seatPublicId() != null) {
                throw invalid("A GENERAL_ADMISSION zone has no seats: leave seatPublicId out");
            }
            return null;
        }
        if (request.seatPublicId() == null) {
            throw invalid("seatPublicId is required for a SEATED zone");
        }
        return seatRepository.findByPublicIdAndZoneId(request.seatPublicId(), zone.getId())
                .orElseThrow(() -> invalid("The seat does not exist in this zone"));
    }

    /** Mirrors the CHECK constraints of the auctions table (and the show time), so the client gets a clear 400. */
    private void validatePricesAndTimes(CreateAuctionRequest r, Event event) {
        if (r.reservePrice() != null && r.reservePrice() < r.startPrice()) {
            throw invalid("reservePrice must be at least startPrice");
        }
        if (!r.startsAt().isAfter(clock.instant())) {
            throw invalid("startsAt must be in the future");
        }
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw invalid("endsAt must be after startsAt");
        }
        if (r.endsAt().isAfter(event.getStartsAt())) {
            throw invalid("The auction must end no later than the event starts");
        }
    }

    private static BusinessException seatUnavailable(UUID seatPublicId) {
        return new BusinessException(ErrorCode.SEAT_UNAVAILABLE, "The seat is not available for an auction",
                Map.of("seatPublicIds", List.of(seatPublicId)));
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, detail);
    }
}
