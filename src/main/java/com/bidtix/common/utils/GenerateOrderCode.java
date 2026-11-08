package com.bidtix.common.utils;

public final class GenerateOrderCode {
    public static String generate() {
        return "ORDER_" + System.currentTimeMillis();
    }
}
