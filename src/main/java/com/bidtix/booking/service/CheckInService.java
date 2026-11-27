package com.bidtix.booking.service;

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
import java.util.UUID;

/**
 * Admits a ticket at the gate: VALID -&gt; USED, exactly once, however many scanners read the same QR code at the same
 * moment.
 * <p>
 * Skeleton: it does nothing yet.
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    /**
     * How long before the event starts the gate opens. A placeholder: decide the real value. Keep it shorter than the
     * 3 days' notice of {@code EventService.cancel}: that is why no ticket can be USED when an event is cancelled.
     */
    static final Duration OPENS_BEFORE_START = Duration.ofHours(2);

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public CheckInResponse checkIn(UUID organizerPublicId, UUID eventPublicId, UUID ticketPublicId) {
        // TODO 1. the event: EVENT_NOTFOUND if unknown, FORBIDDEN if the caller is not its organizer
        // TODO 2. it must be PUBLISHED, and now within [startsAt - OPENS_BEFORE_START, endsAt]: CHECK_IN_NOT_OPEN
        // TODO 3. ONE conditional UPDATE in TicketRepository: USED, used_at and updated_at = now, where the ticket's
        //         public id and event id match and status = VALID. The row count decides; never check in Java first.
        //         (ck_tickets_used_at: used_at must be set exactly when the status is USED)
        // TODO 4. 1 row: return the response (one query that joins event, zone, seat, owner and order, like
        //         AdminTicketResponse)
        // TODO 5. 0 rows: read the ticket to say why. Unknown or of another event: TICKET_NOT_FOUND;
        //         USED: TICKET_ALREADY_USED with usedAt as an extra field; CANCELLED: TICKET_CANCELLED
        Event event = eventRepository.findByPublicId(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));

        User user = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));

        if(!event.getOrganizerId().equals(user.getId())){
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if(event.getStatus() != EventStatus.PUBLISHED ||
                clock.instant().isBefore(event.getStartsAt().minus(OPENS_BEFORE_START)) || clock.instant().isAfter(event.getEndsAt())){
            throw new BusinessException(ErrorCode.CHECK_IN_NOT_OPEN);
        }

        var checkInCount = ticketRepository.checkInTicket(ticketPublicId, event.getId(), clock.instant());
        if(checkInCount == 0){
            var ticket = ticketRepository.findByPublicId(ticketPublicId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));

            if(ticket.getStatus() == com.bidtix.booking.domain.TicketStatus.USED){
                throw new BusinessException(ErrorCode.TICKET_ALREADY_USED, "Ticket already used at " + ticket.getUsedAt());
            } else if(ticket.getStatus() == com.bidtix.booking.domain.TicketStatus.CANCELLED){
                throw new BusinessException(ErrorCode.TICKET_CANCELLED);
            } else {
                throw new BusinessException(ErrorCode.TICKET_NOT_VALID);
            }
        }
        return ticketRepository.checkInTicketResponse(ticketPublicId, event.getId());
    }
}
