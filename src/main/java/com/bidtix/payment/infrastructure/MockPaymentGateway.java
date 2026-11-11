package com.bidtix.payment.infrastructure;

import com.bidtix.payment.service.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stand-in until a real provider is integrated: every payment succeeds. */
@Slf4j
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public boolean charge(String orderCode, long amount) {
        log.info("[MOCK PAYMENT] order {} paid {} VND", orderCode, amount);
        return true;
    }
}
