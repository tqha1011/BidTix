package com.bidtix.booking.service;

import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.AdminTicketResponse;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.common.dtos.PageResponse;
import com.bidtix.event.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminTicketService {

    static final int MAX_PAGE_SIZE = 100;

    private final TicketRepository ticketRepository;
    private final EventService eventService;

    /**
     * Issued tickets of one event, oldest first. {@code status} is optional.
     * Bad paging values are corrected rather than rejected (negative page -> 0, size clamped to 1..100).
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminTicketResponse> listForEvent(UUID eventPublicId, TicketStatus status,
                                                          int page, int size) {
        long eventId = eventService.getEventIdByPublicId(eventPublicId);
        var statuses = status == null ? EnumSet.allOf(TicketStatus.class) : EnumSet.of(status);
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(ticketRepository.findAdminViewByEvent(eventId, statuses, pageable));
    }
}
