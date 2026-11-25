package com.bidtix.booking.service;

import com.bidtix.booking.dtos.MyTicketResponse;
import com.bidtix.booking.infrastructure.TicketRepository;
import com.bidtix.common.dtos.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The tickets a customer owns. */
@Service
@RequiredArgsConstructor
public class MyTicketService {

    static final int MAX_PAGE_SIZE = 100;

    private final TicketRepository ticketRepository;

    /** The caller's tickets, newest first. Bad paging values are corrected (negative page -> 0, size clamped to 1..100). */
    @Transactional(readOnly = true)
    public PageResponse<MyTicketResponse> list(UUID userPublicId, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(ticketRepository.findTicketsOfOwner(userPublicId, pageable));
    }
}
