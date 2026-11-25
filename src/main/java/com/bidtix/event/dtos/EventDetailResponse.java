package com.bidtix.event.dtos;

import java.util.List;

/** One event with its zones, in the order they were created. */
public record EventDetailResponse(EventResponse event, List<ZoneAvailabilityResponse> zones) {
}
