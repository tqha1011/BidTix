package com.bidtix.payment.controller;

import com.bidtix.config.security.CurrentUser;
import com.bidtix.payment.dtos.PaymentUrlResponse;
import com.bidtix.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** Paying an order. Only customers pay; the default per-user rate limit applies. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Starts paying one of the logged-in customer's unpaid orders (a booking order or an auction order): records a payment
     * attempt and returns the VNPAY page to redirect the browser to. 201 with {@code paymentUrl}; it says nothing about the
     * result, which reaches the server in VNPAY's callback. Calling it again makes another attempt.
     * 404 {@code ORDER_NOTFOUND} for an unknown order or someone else's; 409 {@code ORDER_NOT_PAYABLE} when it is not
     * PENDING_PAYMENT or is about to expire. Needs an {@code Idempotency-Key} header (checked by {@code IdempotencyFilter}):
     * a retry with the same key replays the first URL instead of creating another attempt.
     */
    @PostMapping("/{orderCode}/payments")
    @PreAuthorize("hasRole('CUSTOMER')")
    // read by IdempotencyFilter, not by this method; declared here so Swagger UI shows the header
    @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
            description = "Client-made unique key (1 to 64 characters, e.g. a UUID). Retry with the same key to get the first "
                    + "payment page back instead of creating another attempt.")
    public ResponseEntity<PaymentUrlResponse> pay(@PathVariable String orderCode, Principal principal,
                                                  HttpServletRequest request) {
        String url = paymentService.createUrlPayment(orderCode, CurrentUser.publicId(principal), request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(new PaymentUrlResponse(url));
    }
}
