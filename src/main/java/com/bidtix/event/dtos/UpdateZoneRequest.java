package com.bidtix.event.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A zone's new name and price, and for a GENERAL_ADMISSION zone optionally its capacity. The type of a zone never
 * changes, and the seats of a SEATED zone do not either: remove the zone and create it again.
 */
public record UpdateZoneRequest(
        @NotBlank @Size(max = 100) String name,
        /* VND */
        @Positive long price,
        @Positive Integer capacity) {
}
