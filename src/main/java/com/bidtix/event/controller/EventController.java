package com.bidtix.event.controller;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.security.CurrentUser;
import com.bidtix.event.dtos.CreateEventRequest;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.EventDetailResponse;
import com.bidtix.event.dtos.EventResponse;
import com.bidtix.event.dtos.SeatResponse;
import com.bidtix.event.dtos.UpdateEventRequest;
import com.bidtix.event.dtos.UpdateZoneRequest;
import com.bidtix.event.dtos.ZoneResponse;
import com.bidtix.event.service.EventService;
import com.bidtix.event.service.ZoneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/**
 * Events. Creating, zoning and publishing are organizer endpoints (the caller comes from the JWT; who owns an event
 * is checked in the service); the list of published events is open to every logged-in user.
 */
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

    /** Changes a zone of a draft (name, price, standing capacity). Only the event's organizer. */
    @PutMapping("/{eventPublicId}/zones/{zonePublicId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public ZoneResponse updateZone(@PathVariable UUID eventPublicId, @PathVariable UUID zonePublicId,
                                   @Valid @RequestBody UpdateZoneRequest request, Principal principal) {
        return zoneService.updateZone(CurrentUser.publicId(principal), eventPublicId, zonePublicId, request);
    }

    /** Removes a zone of a draft with its seats: 204; 409 {@code EVENT_NOT_EDITABLE} once it has orders. */
    @DeleteMapping("/{eventPublicId}/zones/{zonePublicId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public ResponseEntity<Void> deleteZone(@PathVariable UUID eventPublicId, @PathVariable UUID zonePublicId,
                                           Principal principal) {
        zoneService.deleteZone(CurrentUser.publicId(principal), eventPublicId, zonePublicId);
        return ResponseEntity.noContent().build();
    }

    /** DRAFT -> PUBLISHED. The event must have at least one zone; only its organizer may do this. */
    @PostMapping("/{eventPublicId}/publish")
    @PreAuthorize("hasRole('ORGANIZER')")
    public EventResponse publish(@PathVariable UUID eventPublicId, Principal principal) {
        return eventService.publish(CurrentUser.publicId(principal), eventPublicId);
    }

    /**
     * Replaces an event's data. A draft may change entirely, a published event only its title and description (409
     * {@code EVENT_NOT_EDITABLE} otherwise). Only its organizer.
     */
    @PutMapping("/{eventPublicId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public EventResponse update(@PathVariable UUID eventPublicId, @Valid @RequestBody UpdateEventRequest request,
                                Principal principal) {
        return eventService.update(CurrentUser.publicId(principal), eventPublicId, request);
    }

    /**
     * Deletes a draft with its zones and seats: 204. 409 {@code EVENT_NOT_EDITABLE} when it is not a draft or already has
     * orders. Only its organizer.
     */
    @DeleteMapping("/{eventPublicId}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public ResponseEntity<Void> delete(@PathVariable UUID eventPublicId, Principal principal) {
        eventService.delete(CurrentUser.publicId(principal), eventPublicId);
        return ResponseEntity.noContent().build();
    }

    /** The logged-in organizer's own events, drafts included, newest first. */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('ORGANIZER')")
    public PageResponse<EventResponse> mine(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size, Principal principal) {
        return eventService.listMine(CurrentUser.publicId(principal), page, size);
    }

    /**
     * One event with its zones, their prices and what is left. Published events for any logged-in user, a draft only for
     * its organizer (404 {@code EVENT_NOTFOUND} for anybody else).
     */
    @GetMapping("/{eventPublicId}")
    public EventDetailResponse detail(@PathVariable UUID eventPublicId, Principal principal) {
        return eventService.getDetail(CurrentUser.publicId(principal), eventPublicId);
    }

    /** Every seat of a seated zone with its status, by row and number. Visible like the event itself. */
    @GetMapping("/{eventPublicId}/zones/{zonePublicId}/seats")
    public List<SeatResponse> seats(@PathVariable UUID eventPublicId, @PathVariable UUID zonePublicId,
                                    Principal principal) {
        return eventService.getSeats(CurrentUser.publicId(principal), eventPublicId, zonePublicId);
    }

    /** Published events that have not started yet, soonest first. Any logged-in user. */
    @GetMapping
    public PageResponse<EventResponse> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return eventService.listPublished(page, size);
    }
}
