package com.bidtix.booking.controller;

import com.bidtix.booking.dtos.BaseBookingTicketResult;
import com.bidtix.booking.dtos.BookingTicketRequest;
import com.bidtix.booking.dtos.OrderDetailResponse;
import com.bidtix.booking.dtos.OrderSummaryResponse;
import com.bidtix.booking.service.MyOrderService;
import com.bidtix.common.dtos.PageResponse;
import com.bidtix.booking.service.BookingService;
import com.bidtix.config.security.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MyOrderService myOrderService;

    /** The logged-in customer's orders, newest first. */
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<OrderSummaryResponse> list(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size, Principal principal) {
        return myOrderService.list(CurrentUser.publicId(principal), page, size);
    }

    /** One of the logged-in customer's orders with its lines; 404 {@code ORDER_NOTFOUND} for an unknown or foreign one. */
    @GetMapping("/{orderCode}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderDetailResponse get(@PathVariable String orderCode, Principal principal) {
        return myOrderService.get(CurrentUser.publicId(principal), orderCode);
    }

    /**
     * Books seats (SEATED zone) or a quantity (GENERAL_ADMISSION zone) for the logged-in customer and holds them. It does not
     * take payment. 201 with the order: {@code status} PENDING_PAYMENT and no tickets; the stock stays held until
     * {@code expiresAt}, and the tickets are issued once the order is paid. 409 {@code SEAT_UNAVAILABLE} (with the
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
