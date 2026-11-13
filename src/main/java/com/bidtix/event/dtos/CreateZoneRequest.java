package com.bidtix.event.dtos;

import com.bidtix.event.domain.ZoneType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * SEATED zone: send {@code rows}; the capacity is the number of seats they describe.
 * GENERAL_ADMISSION zone: send {@code capacity} and no rows.
 * Which of the two is required depends on {@code zoneType}, so {@code ZoneService} checks it.
 */
public record CreateZoneRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ZoneType zoneType,
        /* VND */
        @Positive long price,
        @Positive Integer capacity,
        @Size(max = 100) List<@Valid SeatRowRequest> rows) {

    /** Seats {@code fromSeat..toSeat} (both included) of one row, e.g. row "A", seats 1..20. */
    public record SeatRowRequest(
            @NotBlank @Size(max = 10) String rowLabel,
            @Min(1) int fromSeat,
            @Min(1) int toSeat) {
    }
}
