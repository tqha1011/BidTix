package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.CheckInRequest;
import com.bidtix.booking.dtos.CheckInResponse;
import com.bidtix.booking.service.CheckInService;
import com.bidtix.config.ratelimit.RateLimited;
import com.bidtix.config.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/** The gate of an event. There is no staff role yet, so the event's organizer scans. */
@RestController
@RequestMapping("/api/v1/events/{eventPublicId}/check-ins")
@RequiredArgsConstructor
public class CheckInController {

    private final CheckInService checkInService;

    /** Admits one ticket. Its own rate limit: a gate scans far more often than the default limit allows. */
    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    @RateLimited(policy = "check-in", by = RateLimited.KeyBy.USER)
    public CheckInResponse checkIn(@PathVariable UUID eventPublicId, @Valid @RequestBody CheckInRequest request,
                                   Principal principal) {
        return checkInService.checkIn(CurrentUser.publicId(principal), eventPublicId, request.ticketPublicId());
    }
}
