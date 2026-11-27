package com.bidtix.booking.dtos;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** What the scanner read: a ticket's public id is its QR payload. */
public record CheckInRequest(@NotNull UUID ticketPublicId) {
}
