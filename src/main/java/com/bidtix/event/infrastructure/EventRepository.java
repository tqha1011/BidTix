package com.bidtix.event.infrastructure;

import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByPublicId(UUID publicId);

    /**
     * Events with the given status that start after {@code after}. The venue is fetched in the same query:
     * {@code EventResponse.from} reads it, and one lazy load per row would be an N+1. A fetch join on a
     * single-valued association is safe with paging.
     */
    @EntityGraph(attributePaths = "venue")
    Page<Event> findByStatusAndStartsAtAfter(EventStatus status, Instant after, Pageable pageable);

    /** One organizer's events, whatever their status. The venue is fetched in the same query, as above. */
    @EntityGraph(attributePaths = "venue")
    Page<Event> findByOrganizerId(Long organizerId, Pageable pageable);

    /**
     * DRAFT -&gt; PUBLISHED, only if the event is still a DRAFT. Returns the number of rows changed: 0 means somebody
     * else changed the event first. A bulk update does not run {@code @UpdateTimestamp}, so {@code updated_at} is set
     * here, and the persistence context is cleared so the next read of the event sees the new status.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Event e set e.status = com.bidtix.event.domain.EventStatus.PUBLISHED, e.version = e.version + 1, "
            + "e.updatedAt = :now where e.id = :id and e.status = com.bidtix.event.domain.EventStatus.DRAFT")
    int publish(Long id, Instant now);
}
