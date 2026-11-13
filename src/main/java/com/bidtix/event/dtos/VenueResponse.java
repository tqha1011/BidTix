package com.bidtix.event.dtos;

import com.bidtix.event.domain.Venue;

import java.util.UUID;

public record VenueResponse(UUID publicId, String name, String address, String city) {

    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getPublicId(), venue.getName(), venue.getAddress(), venue.getCity());
    }
}
