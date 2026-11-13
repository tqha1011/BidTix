package com.bidtix.event.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.VenueRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventService {

    /** Same default as the {@code events.max_tickets_per_user} column. */
    static final int DEFAULT_MAX_TICKETS_PER_USER = 4;

    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;
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
