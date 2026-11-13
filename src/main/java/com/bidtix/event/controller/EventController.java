package com.bidtix.event.controller;

import com.bidtix.config.security.CurrentUser;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.ZoneResponse;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.ZoneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/** Organizer endpoints. The caller comes from the JWT; who owns an event is checked in the service. */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final ZoneService zoneService;

    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request,
                                                     Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventService.createEvent(CurrentUser.publicId(principal), request));
    }

    /** Creates a zone and, for SEATED zones, all its seats in one request. */
    @PostMapping("/{eventPublicId}/zones")
    @PreAuthorize("hasRole('ORGANIZER')")
    public ResponseEntity<ZoneResponse> createZone(@PathVariable UUID eventPublicId,
                                                   @Valid @RequestBody CreateZoneRequest request,
                                                   Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(zoneService.createZone(CurrentUser.publicId(principal), eventPublicId, request));
    }
}
