package com.bidtix.booking.controller;

import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.AdminTicketResponse;
import com.bidtix.booking.service.AdminTicketService;
import com.bidtix.common.dtos.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events/{eventPublicId}/tickets")
@RequiredArgsConstructor
public class AdminTicketController {

    private final AdminTicketService adminTicketService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AdminTicketResponse> list(@PathVariable UUID eventPublicId,
                                                  @RequestParam(required = false) TicketStatus status,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return adminTicketService.listForEvent(eventPublicId, status, page, size);
    }
}
