package com.bidtix.event.service;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.CacheConfig;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.EventDetailResponse;
import com.bidtix.event.dtos.ZoneAvailabilityResponse;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.event.infrastructure.VenueRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    /** Same default as the {@code events.max_tickets_per_user} column. */
    static final int DEFAULT_MAX_TICKETS_PER_USER = 4;

    static final int MAX_PAGE_SIZE = 100;

    private final EventRepository eventRepository;
    private final TicketZoneRepository ticketZoneRepository;
    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;
    private final Clock clock;
    // Known exception to the module rules (same as auth): the user module has no service yet.
    private final UserRepository userRepository;

    /** Creates a DRAFT event owned by the caller. */
    @Transactional
    public EventResponse createEvent(UUID organizerPublicId, CreateEventRequest request) {
        validateTimes(request);

        User organizer = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        Venue venue = venueRepository.findByPublicId(request.venuePublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOTFOUND));

        int maxTickets = request.maxTicketsPerUser() != null
                ? request.maxTicketsPerUser() : DEFAULT_MAX_TICKETS_PER_USER;

        Event event = eventRepository.save(new Event(organizer.getId(), venue, request.title().trim(),
                request.description(), request.startsAt(), request.endsAt(),
                request.saleStartsAt(), request.saleEndsAt(), maxTickets));
        return EventResponse.from(event);
    }

    /**
     * One event with its zones and what is left in each. A published event is open to every logged-in user; any other
     * status only to its organizer, and looks "not found" to everybody else. Not cached: availability changes with every
     * booking.
     */
    @Transactional(readOnly = true)
    public EventDetailResponse getDetail(UUID callerPublicId, UUID eventPublicId) {
        Event event = findVisibleEvent(callerPublicId, eventPublicId);

        Map<Long, Long> freeSeats = seatRepository.countAvailableByEventId(event.getId()).stream()
                .collect(Collectors.toMap(SeatRepository.ZoneSeats::getZoneId, SeatRepository.ZoneSeats::getAvailable));
        List<ZoneAvailabilityResponse> zones = ticketZoneRepository.findByEventIdOrderById(event.getId()).stream()
                .map(zone -> ZoneAvailabilityResponse.from(zone, zone.getAvailableQuantity() != null
                        ? zone.getAvailableQuantity()
                        : freeSeats.getOrDefault(zone.getId(), 0L).intValue()))
                .toList();
        return new EventDetailResponse(EventResponse.from(event), zones);
    }

    /** The event if the caller may see it: published, or their own. Call it inside a transaction. */
    private Event findVisibleEvent(UUID callerPublicId, UUID eventPublicId) {
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        if (event.getStatus() != EventStatus.PUBLISHED && !userRepository.findByPublicId(callerPublicId)
                .map(user -> user.getId().equals(event.getOrganizerId())).orElse(false)) {
            throw new BusinessException(ErrorCode.EVENT_NOTFOUND);
        }
        return event;
    }

    /**
     * DRAFT -&gt; PUBLISHED. Only the organizer who owns the event, and only if it has at least one zone.
     * The state change is one conditional UPDATE whose row count is checked, so two parallel publishes cannot both
     * win: the loser gets {@code EVENT_NOT_EDITABLE}.
     *
     * <p>Evicts every cached page of the public list (a published event belongs on it now). Anything else that adds
     * or removes events from that list (a future cancel) must evict {@link CacheConfig#EVENTS} too.
     */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.EVENTS, allEntries = true)
    public EventResponse publish(UUID organizerPublicId, UUID eventPublicId) {
        User organizer = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));

        if (!event.getOrganizerId().equals(organizer.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE);
        }
        if (!ticketZoneRepository.existsByEventId(event.getId())) {
            throw new BusinessException(ErrorCode.EVENT_HAS_NO_ZONES);
        }

        if (eventRepository.publish(event.getId(), clock.instant()) != 1) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE);   // somebody else got there first
        }
        // the bulk update cleared the persistence context, so this loads the event with its new status
        return EventResponse.from(eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND)));
    }

    /**
     * Published events that have not started yet, soonest first. Paging values are corrected rather than rejected
     * (negative page -&gt; 0, size clamped to 1..100), like the admin ticket list.
     *
     * <p>Cached per (page, size), see {@link #pageKey}. The key uses the corrected values on purpose: otherwise
     * {@code size=1000} and {@code size=100} would be two entries for the same data, and any caller could fill
     * Redis by walking through page numbers. The cache is a hint for reads only; entries live for the TTL in
     * application.yaml and are cleared when an event is published. {@code sync = true} (with the locking cache writer in
     * {@code CacheConfig}) lets one request load an empty page while the others wait for it, instead of all of them
     * querying PostgreSQL at once.
     */
    @Cacheable(cacheNames = CacheConfig.EVENTS, sync = true,
            key = "T(com.bidtix.event.service.EventService).pageKey(#page, #size)")
    @Transactional(readOnly = true)
    public PageResponse<EventResponse> listPublished(int page, int size) {
        // id is the tie-breaker, so two events that start at the same time never swap between pages
        var pageable = PageRequest.of(clampPage(page), clampSize(size), Sort.by("startsAt", "id"));
        return PageResponse.from(eventRepository
                .findByStatusAndStartsAtAfter(EventStatus.PUBLISHED, clock.instant(), pageable)
                .map(EventResponse::from));
    }

    /** The cache key of a list page, built from the corrected paging values. Public because SpEL calls it. */
    public static String pageKey(int page, int size) {
        return clampPage(page) + ":" + clampSize(size);
    }

    private static int clampPage(int page) {
        return Math.max(page, 0);
    }

    private static int clampSize(int size) {
        return Math.clamp(size, 1, MAX_PAGE_SIZE);
    }

    /** For other modules: resolves the public id used in URLs to the internal id. */
    @Transactional(readOnly = true)
    public long getEventIdByPublicId(UUID eventPublicId) {
        return eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND))
                .getId();
    }

    /** Mirrors {@code ck_events_show_time} and {@code ck_events_sale_window}, so the client gets a clear 400. */
    private static void validateTimes(CreateEventRequest r) {
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw invalid("endsAt must be after startsAt");
        }
        if (!r.saleEndsAt().isAfter(r.saleStartsAt())) {
            throw invalid("saleEndsAt must be after saleStartsAt");
        }
        if (r.saleEndsAt().isAfter(r.startsAt())) {
            throw invalid("Ticket sales must end no later than startsAt");
        }
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, detail);
    }
}
