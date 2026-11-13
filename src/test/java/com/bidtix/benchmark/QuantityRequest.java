package com.bidtix.benchmark;

/** Take {@code quantity} tickets from a GENERAL_ADMISSION zone ({@code ticket_zones.available_quantity}). */
public record QuantityRequest(long zoneId, int quantity) {
}
