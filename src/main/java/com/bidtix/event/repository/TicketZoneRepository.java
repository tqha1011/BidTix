package com.bidtix.event.repository;

import com.bidtix.event.domain.TicketZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;


public interface TicketZoneRepository extends JpaRepository<TicketZone, Long> {
    Optional<TicketZone> findByPublicId(UUID publicId);
}
