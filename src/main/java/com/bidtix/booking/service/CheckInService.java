package com.bidtix.booking.service;

import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.CheckInResponse;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Admits a ticket at the gate: VALID -&gt; USED, exactly once, however many scanners read the same QR code at the same
 * moment: one conditional UPDATE decides, and its row count is checked.
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    /**
     * How long before the event starts the gate opens; it closes when the event ends. Keep it shorter than the 3 days'
     * notice of {@code EventService.cancel}: that is why no ticket can be USED when an event is cancelled.
     */
    static final Duration OPENS_BEFORE_START = Duration.ofHours(2);

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Admits one ticket of this event. Only the event's organizer, only while the event is PUBLISHED and its gate is open.
     * A ticket of another event is "not found", whatever its state, so nothing is told about it.
     */
    @Transactional
    public CheckInResponse checkIn(UUID organizerPublicId, UUID eventPublicId, UUID ticketPublicId) {
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        User user = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        if (!event.getOrganizerId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Instant now = clock.instant();
        if (event.getStatus() != EventStatus.PUBLISHED
                || now.isBefore(event.getStartsAt().minus(OPENS_BEFORE_START)) || now.isAfter(event.getEndsAt())) {
            throw new BusinessException(ErrorCode.CHECK_IN_NOT_OPEN);
        }

        if (ticketRepository.checkInTicket(ticketPublicId, event.getId(), now) != 1) {
            throw refusal(ticketPublicId, event.getId());
        }
        return ticketRepository.checkInTicketResponse(ticketPublicId, event.getId());
    }

    /** Why the UPDATE changed nothing. A ticket never becomes VALID again, so the answer cannot be outdated. */
    private BusinessException refusal(UUID ticketPublicId, Long eventId) {
        Ticket ticket = ticketRepository.findByPublicIdAndEventId(ticketPublicId, eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
        if (ticket.getStatus() == TicketStatus.USED) {
            return new BusinessException(ErrorCode.TICKET_ALREADY_USED, "The ticket was already used",
                    Map.of("usedAt", ticket.getUsedAt()));
        }
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return new BusinessException(ErrorCode.TICKET_CANCELLED);
        }
        throw new IllegalStateException("Valid ticket " + ticketPublicId + " could not be checked in");
    }
}
