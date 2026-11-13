package com.bidtix.event.dtos;

import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID publicId,
        UUID venuePublicId,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        Instant saleStartsAt,
        Instant saleEndsAt,
        int maxTicketsPerUser,
        EventStatus status) {

    /** The venue is loaded lazily, so call this inside the transaction that loaded the event. */
    public static EventResponse from(Event event) {
        return new EventResponse(event.getPublicId(), event.getVenue().getPublicId(), event.getTitle(),
                event.getDescription(), event.getStartsAt(), event.getEndsAt(), event.getSaleStartsAt(),
                event.getSaleEndsAt(), event.getMaxTicketsPerUser(), event.getStatus());
    }
}
