package com.bidtix.payment.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The answer to VNPAY's confirmation; VNPAY reads exactly the names {@code RspCode} and {@code Message}. */
public record TransactionResponse(@JsonProperty("RspCode") String RspCode, @JsonProperty("Message") String message) {
    public static TransactionResponse create(String RspCode, String message) {
        return new TransactionResponse(RspCode, message);
    }
}
