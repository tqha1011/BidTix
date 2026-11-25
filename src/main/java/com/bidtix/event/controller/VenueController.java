package com.bidtix.event.controller;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.event.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueResponse> create(@Valid @RequestBody CreateVenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(venueService.create(request));
    }

    /** Venues by name, for any logged-in user: an organizer picks one for an event. */
    @GetMapping
    public PageResponse<VenueResponse> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return venueService.list(page, size);
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasRole('ADMIN')")
    public VenueResponse update(@PathVariable UUID publicId, @Valid @RequestBody CreateVenueRequest request) {
        return venueService.update(publicId, request);
    }

    /** 204; 409 {@code VENUE_IN_USE} while any event uses the venue. */
    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID publicId) {
        venueService.delete(publicId);
        return ResponseEntity.noContent().build();
    }

    /** Any authenticated user may read a venue; the value is served from the Redis cache when it is there. */
    @GetMapping("/{publicId}")
    public VenueResponse get(@PathVariable UUID publicId) {
        return venueService.getByPublicId(publicId);
    }
}
