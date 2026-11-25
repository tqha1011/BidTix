package com.bidtix.event.dtos;

import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;

import java.util.UUID;

/**
 * A zone as a buyer sees it. {@code available} is how many tickets could be bought when it was read (the counter of a
 * standing zone, the free seats of a seated one): a hint only, the booking itself decides.
 */
public record ZoneAvailabilityResponse(UUID publicId, String name, ZoneType zoneType, long price, int capacity,
                                       int available) {

    public static ZoneAvailabilityResponse from(TicketZone zone, int available) {
        return new ZoneAvailabilityResponse(zone.getPublicId(), zone.getName(), zone.getZoneType(), zone.getPrice(),
                zone.getCapacity(), available);
    }
}
