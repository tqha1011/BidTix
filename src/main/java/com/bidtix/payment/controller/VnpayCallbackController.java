package com.bidtix.payment.controller;

import com.bidtix.config.ratelimit.RateLimited;
import com.bidtix.payment.dtos.TransactionResponse;
import com.bidtix.payment.dtos.VnPayResponse;
import com.bidtix.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * What VNPAY calls. These routes need no login (see the security whitelist): the request's signature is what proves it
 * comes from VNPAY, so every route here must check it and be {@code @RateLimited} by IP.
 */
@RestController
@RequestMapping("/api/v1/payments/vnpay")
@RequiredArgsConstructor
public class VnpayCallbackController {

    private final PaymentService paymentService;

    /**
     * VNPAY's server-to-server confirmation of one payment attempt (IPN). Always 200 with {@code RspCode} and
     * {@code Message} once the request reaches the service (see {@link PaymentService#processVnPayResponse}); 400 when a
     * parameter cannot be read (an amount that is not a number), 429 above the rate limit. VNPAY retries after both.
     * The IPN URL is configured on VNPAY's side, not sent with the payment request.
     *
     * @param params   every query parameter as received, which the signature check needs
     * @param response the same parameters, bound by their {@code vnp_} names
     */
    @GetMapping("/ipn")
    @RateLimited(policy = "vnpay-ipn")
    public TransactionResponse ipn(@RequestParam Map<String, String> params, VnPayResponse response) {
        return paymentService.processVnPayResponse(params, response);
    }


    
}
