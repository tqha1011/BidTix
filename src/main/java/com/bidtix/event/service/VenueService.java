package com.bidtix.event.service;

import com.bidtix.common.dtos.PageResponse;
import com.bidtix.config.CacheConfig;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Venue;
import com.bidtix.event.dtos.CreateVenueRequest;
import com.bidtix.event.dtos.VenueResponse;
import com.bidtix.event.infrastructure.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VenueService {

    static final int MAX_PAGE_SIZE = 100;

    private final VenueRepository venueRepository;

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.VENUE_PAGES, allEntries = true)
    public VenueResponse create(CreateVenueRequest request) {
        Venue venue = venueRepository.save(
                new Venue(request.name().trim(), request.address().trim(), request.city().trim()));
        return VenueResponse.from(venue);
    }

    /**
     * Venues by name. Bad paging values are corrected (negative page -> 0, size clamped to 1..100).
     *
     * <p>Cached per corrected (page, size), see {@link #pageKey}, so the spellings of one page share an entry.
     * Entries live for the TTL in application.yaml and are all cleared by {@link #create}, {@link #update} and
     * {@link #delete}: one change can move venues between pages.
     */
    @Cacheable(cacheNames = CacheConfig.VENUE_PAGES, sync = true,
            key = "T(com.bidtix.event.service.VenueService).pageKey(#page, #size)")
    @Transactional(readOnly = true)
    public PageResponse<VenueResponse> list(int page, int size) {
        var pageable = PageRequest.of(clampPage(page), clampSize(size), Sort.by("name", "id"));
        return PageResponse.from(venueRepository.findAll(pageable).map(VenueResponse::from));
    }

    /** Replaces a venue's data. The cached entry and the cached list pages are evicted after the commit. */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.VENUES, key = "#publicId"),
            @CacheEvict(cacheNames = CacheConfig.VENUE_PAGES, allEntries = true)})
    public VenueResponse update(UUID publicId, CreateVenueRequest request) {
        Venue venue = venueRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOTFOUND));
        venue.relocate(request.name().trim(), request.address().trim(), request.city().trim());
        return VenueResponse.from(venueRepository.saveAndFlush(venue));
    }

    /** Removes a venue no event uses; the foreign key decides ({@code VENUE_IN_USE}). */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.VENUES, key = "#publicId"),
            @CacheEvict(cacheNames = CacheConfig.VENUE_PAGES, allEntries = true)})
    public void delete(UUID publicId) {
        Venue venue = venueRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOTFOUND));
        try {
            venueRepository.delete(venue);
            venueRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.VENUE_IN_USE);
        }
    }

    /**
     * Cache-aside (the TTL is in application.yaml); {@link #update} and {@link #delete} evict the entry. A venue that
     * does not exist throws, so "not found" is never cached.
     * The cache works through a proxy: call this from another bean (the controller), not from this class.
     * {@code sync = true} (with the locking cache writer in {@code CacheConfig}) makes one request load a missing
     * venue while the others wait, instead of all of them querying PostgreSQL.
     */
    @Cacheable(cacheNames = CacheConfig.VENUES, key = "#publicId", sync = true)
    @Transactional(readOnly = true)
    public VenueResponse getByPublicId(UUID publicId) {
        return venueRepository.findByPublicId(publicId)
                .map(VenueResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOTFOUND));
    }

    /** The cache key of a list page, built from the corrected paging values. Public because SpEL calls it. */
    public static String pageKey(int page, int size) {
        return clampPage(page) + ":" + clampSize(size);
    }

    private static int clampPage(int page) {
        return Math.max(page, 0);
    }

    private static int clampSize(int size) {
        return Math.clamp(size, 1, MAX_PAGE_SIZE);
    }
}
