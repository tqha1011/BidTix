package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.service.BookingService;
import com.bidtix.config.security.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** Orders. Only customers buy; the default per-user rate limit applies. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final BookingService bookingService;

    /**
     * Books seats (SEATED zone) or a quantity (GENERAL_ADMISSION zone) for the logged-in customer, holds them and takes
     * the payment. 201 with the order: {@code status} PAID with the issued tickets, or PENDING_PAYMENT without tickets when
     * the payment did not go through (the stock stays held until {@code expiresAt}). 409 {@code SEAT_UNAVAILABLE} (with the
     * lost {@code seatPublicIds}) or {@code ZONE_SOLD_OUT} when the stock is gone; 404 for an unknown event or zone.
     * Needs an {@code Idempotency-Key} header (checked by {@code IdempotencyFilter}): a retry with the same key and body
     * replays the first response instead of booking twice.
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    // read by IdempotencyFilter, not by this method; declared here so Swagger UI shows the header
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            description = "Client-made unique key (1 to 64 characters, e.g. a UUID). Retry with the same key and body to get the "
                    + "first response back instead of booking twice.")
    public ResponseEntity<BaseBookingTicketResult> book(@Valid @RequestBody BookingTicketRequest request,
                                                        Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.bookTicket(request, CurrentUser.publicId(principal)));
    }
}
