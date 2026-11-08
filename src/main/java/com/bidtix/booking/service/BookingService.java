package com.bidtix.booking.service;

import com.bidtix.booking.dto.BookingTicketRequest;
import com.bidtix.booking.dto.BookingTicketResult;
import com.bidtix.booking.repository.OrderRepository;
import com.bidtix.booking.repository.TicketRepository;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.repository.EventRepository;
import com.bidtix.event.repository.TicketZoneRepository;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookingService {
    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final TicketZoneRepository ticketZoneRepository;
    private final UserRepository userRepository;


    /**
     * Books a ticket for a user for a specific event and zone.
     * The transaction contains the following steps:
     * 1. Validate the user,event, zone exists.
     * 2. Validate the zone and seats based on the request.
     * 3. Validate the seat is valid and available if the zone is a seat-based zone and the capacity if the zone is a general admission zone.
     * 4. Create an order and save it to the database.
     * 5. Create tickets for the order and save them to the database.
     * 6. Return the result of the booking operation.
     */

    @Transactional
    public BookingTicketResult bookTicket(BookingTicketRequest request, UUID userPublicId) {
        var user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        var event = eventRepository.findByPublicId(request.eventPublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        var zone = ticketZoneRepository.findByPublicId(request.zonePublicId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
        validateZoneAndSeats(zone, request);
        // Additional logic for booking the ticket can be added here
        return null;
    }

    private void validateZoneAndSeats(TicketZone zone, BookingTicketRequest request) {
        if (zone.getZoneType() == ZoneType.SEATED) {
            if (request.seatPublicIds() == null || request.seatPublicIds().isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "Seat must be specified for seated zones");
            }
            // Additional logic to validate seat availability can be added here
        } else {
            if (request.quantity() == null || request.quantity() <= 0) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "Quantity must be specified for general admission zones");
            }
        }
    }
}
