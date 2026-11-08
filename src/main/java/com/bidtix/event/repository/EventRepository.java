package com.bidtix.event.repository;

import com.bidtix.event.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByPublicId(UUID publicId);
}
