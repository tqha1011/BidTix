package com.bidtix.event.service;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.CacheConfig;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.EventDetailResponse;
import com.bidtix.event.dtos.ZoneAvailabilityResponse;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.SeatResponse;
import com.bidtix.event.dtos.UpdateEventRequest;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.event.infrastructure.VenueRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
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
        validateTimes(request.startsAt(), request.endsAt(), request.saleStartsAt(), request.saleEndsAt());

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

    /**
     * The seat map of one seated zone, visible like the event itself. A zone of another event is "not found"; a standing
     * zone has no seats to show. A snapshot: booking decides whether a seat can still be had.
     */
    @Transactional(readOnly = true)
    public List<SeatResponse> getSeats(UUID callerPublicId, UUID eventPublicId, UUID zonePublicId) {
        Event event = findVisibleEvent(callerPublicId, eventPublicId);
        TicketZone zone = ticketZoneRepository.findByPublicId(zonePublicId)
                .filter(candidate -> candidate.getEvent().getId().equals(event.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
        if (zone.getZoneType() != ZoneType.SEATED) {
            throw invalid("A GENERAL_ADMISSION zone has no seats");
        }
        return seatRepository.findByZoneIdOrderByRowLabelAscSeatNumberAsc(zone.getId()).stream()
                .map(SeatResponse::from)
                .toList();
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
        // locked: the zone check below must still hold when the status changes
        Event event = requireOwner(organizerPublicId, eventRepository.findByPublicIdForUpdate(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND)));
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

    /** The caller's own events, drafts included, newest first. Not cached: an organizer must see their changes at once. */
    @Transactional(readOnly = true)
    public PageResponse<EventResponse> listMine(UUID organizerPublicId, int page, int size) {
        User organizer = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        var pageable = PageRequest.of(clampPage(page), clampSize(size), Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(eventRepository.findByOrganizerId(organizer.getId(), pageable).map(EventResponse::from));
    }

    /**
     * Replaces an event's data. Only its organizer; a DRAFT may change entirely, a PUBLISHED event only its title and
     * description (tickets may have been sold for that place and time), a cancelled or completed one not at all. The
     * entity's version makes a change that races with publishing fail with {@code CONCURRENT_UPDATE}. Evicts the cached
     * public pages, which show the title.
     */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.EVENTS, allEntries = true)
    public EventResponse update(UUID organizerPublicId, UUID eventPublicId, UpdateEventRequest request) {
        validateTimes(request.startsAt(), request.endsAt(), request.saleStartsAt(), request.saleEndsAt());
        Event event = findOwnEvent(organizerPublicId, eventPublicId);
        int maxTickets = request.maxTicketsPerUser() != null ? request.maxTicketsPerUser() : event.getMaxTicketsPerUser();

        if (event.getStatus() == EventStatus.DRAFT) {
            Venue venue = venueRepository.findByPublicId(request.venuePublicId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOTFOUND));
            event.reschedule(venue, request.startsAt(), request.endsAt(), request.saleStartsAt(),
                    request.saleEndsAt(), maxTickets);
        } else if (event.getStatus() != EventStatus.PUBLISHED || maxTickets != event.getMaxTicketsPerUser()
                || !event.getVenue().getPublicId().equals(request.venuePublicId())
                || !sameMoment(event.getStartsAt(), request.startsAt()) || !sameMoment(event.getEndsAt(), request.endsAt())
                || !sameMoment(event.getSaleStartsAt(), request.saleStartsAt())
                || !sameMoment(event.getSaleEndsAt(), request.saleEndsAt())) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE,
                    "Only the title and description of a published event can be changed");
        }
        event.describe(request.title().trim(), request.description());
        return EventResponse.from(eventRepository.saveAndFlush(event));
    }

    /**
     * Removes a DRAFT with its zones and seats, in one transaction, with the event row locked so it cannot be published
     * meanwhile. A draft that already has an order is refused ({@code EVENT_NOT_EDITABLE}) and stays whole: the foreign
     * keys decide. The public list never showed it, so there is nothing to evict.
     */
    @Transactional
    public void delete(UUID organizerPublicId, UUID eventPublicId) {
        Event event = requireOwner(organizerPublicId, eventRepository.findByPublicIdForUpdate(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND)));
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE);
        }
        try {
            seatRepository.deleteByEventId(event.getId());
            ticketZoneRepository.deleteByEventId(event.getId());
            eventRepository.delete(event);
            eventRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.info("Draft event {} is referenced and cannot be deleted: {}", eventPublicId,
                    e.getMostSpecificCause().getMessage());
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE, "The event already has orders and cannot be deleted");
        }
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.EVENTS, allEntries = true)
    public void cancel(UUID organizerPublicId, UUID eventPublicId) {
        Event event = requireOwner(organizerPublicId, eventRepository.findByPublicIdForUpdate(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND)));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE);
        }
        if(eventRepository.cancelEvent(event.getId(), clock.instant()) != 1) {
            log.warn("The event {} was cancelled", event.getTitle());
        }
    }

    /** The caller's event. Call it inside a transaction. */
    private Event findOwnEvent(UUID organizerPublicId, UUID eventPublicId) {
        return requireOwner(organizerPublicId, eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND)));
    }

    private Event requireOwner(UUID organizerPublicId, Event event) {
        User organizer = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        if (!event.getOrganizerId().equals(organizer.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return event;
    }

    /** The database keeps microseconds: a finer difference is no difference. */
    private static boolean sameMoment(Instant stored, Instant sent) {
        return stored.truncatedTo(ChronoUnit.MICROS).equals(sent.truncatedTo(ChronoUnit.MICROS));
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
    private static void validateTimes(Instant startsAt, Instant endsAt, Instant saleStartsAt, Instant saleEndsAt) {
        if (!endsAt.isAfter(startsAt)) {
            throw invalid("endsAt must be after startsAt");
        }
        if (!saleEndsAt.isAfter(saleStartsAt)) {
            throw invalid("saleEndsAt must be after saleStartsAt");
        }
        if (saleEndsAt.isAfter(startsAt)) {
            throw invalid("Ticket sales must end no later than startsAt");
        }
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, detail);
    }
}
