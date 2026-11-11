package com.bidtix.common.utils;

import java.security.SecureRandom;

/**
 * Human-readable order code, e.g. {@code ORDER_K7M2Q9XW4PHD} (18 chars; the column holds 20).
 * <p>
 * It is random, not time-based: a timestamp collides when two orders are created in the same
 * millisecond. The characters leave out 0/O and 1/I/L so a code can be read out over the phone.
 * The unique constraint {@code uq_orders_order_code} stays as the last line of defence.
 */
public final class GenerateOrderCode {

    private static final String PREFIX = "ORDER_";
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";   // 31 symbols
    private static final int RANDOM_LENGTH = 12;                                // 31^12 is about 7.9e17
    private static final SecureRandom RANDOM = new SecureRandom();

    private GenerateOrderCode() {
    }

    public static String generate() {
        StringBuilder code = new StringBuilder(PREFIX.length() + RANDOM_LENGTH).append(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
