package com.bidtix.event.dtos;

import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;

import java.util.UUID;

public record ZoneResponse(UUID publicId, String name, ZoneType zoneType, long price, int capacity, int seatCount) {

    public static ZoneResponse from(TicketZone zone, int seatCount) {
        return new ZoneResponse(zone.getPublicId(), zone.getName(), zone.getZoneType(), zone.getPrice(),
                zone.getCapacity(), seatCount);
    }
}
