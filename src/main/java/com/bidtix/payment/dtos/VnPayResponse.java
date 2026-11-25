package com.bidtix.payment.dtos;

import org.springframework.web.bind.annotation.BindParam;

/**
 * The parameters VNPAY sends back after a payment. All values arrive as VNPAY wrote them; nothing here is trusted until
 * {@code vnp_SecureHash} has been checked, which must happen before any other processing. The check needs every parameter
 * of the request, so run it on the raw parameter map, not on this record.
 *
 * @param tmnCode           the merchant's website code at VNPAY (8 characters)
 * @param amount            the paid amount in VND <b>times 100</b>
 * @param bankCode          the bank's code, e.g. NCB
 * @param bankTranNo        the transaction number at the bank; optional
 * @param cardType          the kind of account or card used, e.g. ATM, QRCODE; optional
 * @param payDate           when the payment was made, yyyyMMddHHmmss; optional
 * @param orderInfo         the description of the payment (Vietnamese without diacritics)
 * @param transactionNo     the transaction number at VNPAY (up to 15 digits)
 * @param responseCode      the result code of the payment; {@code 00} means success
 * @param transactionStatus the state of the transaction at VNPAY; {@code 00} means success, anything else failed
 * @param txnRef            the reference sent to VNPAY in the payment request
 * @param secureHash        the checksum over the other parameters
 */
public record VnPayResponse(
        @BindParam("vnp_TmnCode") String tmnCode,
        @BindParam("vnp_Amount") Long amount,
        @BindParam("vnp_BankCode") String bankCode,
        @BindParam("vnp_BankTranNo") String bankTranNo,
        @BindParam("vnp_CardType") String cardType,
        @BindParam("vnp_PayDate") String payDate,
        @BindParam("vnp_OrderInfo") String orderInfo,
        @BindParam("vnp_TransactionNo") String transactionNo,
        @BindParam("vnp_ResponseCode") String responseCode,
        @BindParam("vnp_TransactionStatus") String transactionStatus,
        @BindParam("vnp_TxnRef") String txnRef,
        @BindParam("vnp_SecureHash") String secureHash) {
}
