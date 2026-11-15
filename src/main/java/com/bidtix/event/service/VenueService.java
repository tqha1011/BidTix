package com.bidtix.event.service;

import com.bidtix.config.CacheConfig;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.event.infrastructure.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VenueService {

    private final VenueRepository venueRepository;

    @Transactional
    public VenueResponse create(CreateVenueRequest request) {
        Venue venue = venueRepository.save(
                new Venue(request.name().trim(), request.address().trim(), request.city().trim()));
        return VenueResponse.from(venue);
    }

    /**
     * Cache-aside (the TTL is in application.yaml). Venues cannot be changed or deleted yet; when an update
     * is added it must evict this entry. A venue that does not exist throws, so "not found" is never cached.
     * The cache works through a proxy: call this from another bean (the controller), not from this class.
     */
    @Cacheable(cacheNames = CacheConfig.VENUES, key = "#publicId")
    @Transactional(readOnly = true)
    public VenueResponse getByPublicId(UUID publicId) {
        return venueRepository.findByPublicId(publicId)
                .map(VenueResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOTFOUND));
    }
}
