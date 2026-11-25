package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.MyTicketResponse;
import com.bidtix.booking.service.MyTicketService;
import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** Tickets. Only customers own them; the default per-user rate limit applies. */
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final MyTicketService myTicketService;

    /** The logged-in customer's tickets, newest first. A ticket exists only for a paid order. */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<MyTicketResponse> list(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size, Principal principal) {
        return myTicketService.list(CurrentUser.publicId(principal), page, size);
    }
}
