package com.bidtix.event.dtos;

import com.bidtix.event.domain.Seat;
import com.bidtix.event.domain.SeatStatus;

import java.util.UUID;

/** One seat of the seat map. Who holds it, and until when, is not told. */
public record SeatResponse(UUID publicId, String rowLabel, int seatNumber, SeatStatus status) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getPublicId(), seat.getRowLabel(), seat.getSeatNumber(), seat.getStatus());
    }
}
