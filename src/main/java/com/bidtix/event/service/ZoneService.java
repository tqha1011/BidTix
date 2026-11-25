package com.bidtix.event.service;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import com.bidtix.event.domain.Event;
import com.bidtix.event.domain.EventStatus;
import com.bidtix.event.domain.TicketZone;
import com.bidtix.event.domain.ZoneType;
import com.bidtix.event.dtos.CreateZoneRequest;
import com.bidtix.event.dtos.CreateZoneRequest.SeatRowRequest;
import com.bidtix.event.dtos.UpdateZoneRequest;
import com.bidtix.event.dtos.ZoneResponse;
import com.bidtix.event.infrastructure.EventRepository;
import com.bidtix.event.infrastructure.SeatBulkInserter;
import com.bidtix.event.infrastructure.SeatRepository;
import com.bidtix.event.infrastructure.TicketZoneRepository;
import com.bidtix.user.domain.User;
import com.bidtix.user.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Creates a ticket zone and, for SEATED zones, all its seats in one transaction: either the whole
 * zone exists afterwards or nothing does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneService {

    static final int MAX_SEATS_PER_REQUEST = 10_000;

    private final EventRepository eventRepository;
    private final TicketZoneRepository ticketZoneRepository;
    private final SeatBulkInserter seatBulkInserter;
    private final SeatRepository seatRepository;
    // Known exception to the module rules (same as auth): the user module has no service yet.
    private final UserRepository userRepository;

    @Transactional
    public ZoneResponse createZone(UUID organizerPublicId, UUID eventPublicId, CreateZoneRequest request) {
        List<SeatRowRequest> rows = request.rows() == null ? List.of() : request.rows();
        int capacity = resolveCapacity(request, rows);

        Event event = lockOwnDraft(organizerPublicId, eventPublicId);

        TicketZone zone;
        try {
            // flush now: a duplicate name must surface here, and the seats need the zone row to exist
            zone = ticketZoneRepository.saveAndFlush(new TicketZone(event, request.name().trim(),
                    request.zoneType(), request.price(), capacity));
        } catch (DataIntegrityViolationException e) {
            // uq_ticket_zones_event_name: two requests (or a retry) used the same name for this event
            log.info("Zone name already used for event {}: {}", eventPublicId, e.getMostSpecificCause().getMessage());
            throw new BusinessException(ErrorCode.ZONE_NAME_TAKEN);
        }

        int seatCount = request.zoneType() == ZoneType.SEATED
                ? seatBulkInserter.insert(zone.getId(), rows)
                : 0;
        return ZoneResponse.from(zone, seatCount);
    }

    /**
     * Changes a zone of a draft: name, price and, for a standing zone, capacity (never below the tickets already taken).
     * The seats of a seated zone stay as they are.
     */
    @Transactional
    public ZoneResponse updateZone(UUID organizerPublicId, UUID eventPublicId, UUID zonePublicId,
                                   UpdateZoneRequest request) {
        Event event = lockOwnDraft(organizerPublicId, eventPublicId);
        TicketZone zone = zoneOf(event, zonePublicId);
        Integer capacity = request.capacity();
        if (capacity != null && capacity != zone.getCapacity()) {
            if (zone.getZoneType() == ZoneType.SEATED) {
                throw invalid("The capacity of a SEATED zone is its seats: remove the zone and create it again");
            }
            if (capacity < zone.takenQuantity()) {
                throw invalid(zone.takenQuantity() + " tickets of this zone are taken: capacity cannot be lower");
            }
            zone.resize(capacity);
        }
        zone.rename(request.name().trim(), request.price());
        try {
            ticketZoneRepository.saveAndFlush(zone);
        } catch (DataIntegrityViolationException e) {
            log.info("Zone name already used for event {}: {}", eventPublicId, e.getMostSpecificCause().getMessage());
            throw new BusinessException(ErrorCode.ZONE_NAME_TAKEN);
        }
        return ZoneResponse.from(zone, zone.getZoneType() == ZoneType.SEATED ? zone.getCapacity() : 0);
    }

    /** Removes a zone of a draft with its seats. A zone that already has an order stays whole: the foreign keys decide. */
    @Transactional
    public void deleteZone(UUID organizerPublicId, UUID eventPublicId, UUID zonePublicId) {
        Event event = lockOwnDraft(organizerPublicId, eventPublicId);
        TicketZone zone = zoneOf(event, zonePublicId);
        try {
            seatRepository.deleteByZoneId(zone.getId());
            ticketZoneRepository.delete(zone);
            ticketZoneRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.info("Zone {} is referenced and cannot be deleted: {}", zonePublicId, e.getMostSpecificCause().getMessage());
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE, "The zone already has orders and cannot be deleted");
        }
    }

    /**
     * The caller's DRAFT event with its row locked, so it cannot be published or deleted until this transaction ends.
     */
    private Event lockOwnDraft(UUID organizerPublicId, UUID eventPublicId) {
        User organizer = userRepository.findByPublicId(organizerPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOTFOUND));
        Event event = eventRepository.findByPublicIdForUpdate(eventPublicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOTFOUND));
        if (!event.getOrganizerId().equals(organizer.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE);
        }
        return event;
    }

    /** A zone of another event is "not found" for this one. */
    private TicketZone zoneOf(Event event, UUID zonePublicId) {
        return ticketZoneRepository.findByPublicId(zonePublicId)
                .filter(zone -> zone.getEvent().getId().equals(event.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOTFOUND));
    }

    /**
     * Checks the zone-type-specific rules and returns the capacity.
     * SEATED: rows are required and the capacity is the number of seats they describe.
     * GENERAL_ADMISSION: capacity is required and rows are not allowed.
     */
    private static int resolveCapacity(CreateZoneRequest request, List<SeatRowRequest> rows) {
        if (request.zoneType() == ZoneType.GENERAL_ADMISSION) {
            if (!rows.isEmpty()) {
                throw invalid("A GENERAL_ADMISSION zone has no seats: remove rows and send capacity");
            }
            if (request.capacity() == null) {
                throw invalid("capacity is required for a GENERAL_ADMISSION zone");
            }
            return request.capacity();
        }

        if (rows.isEmpty()) {
            throw invalid("rows are required for a SEATED zone");
        }
        Set<String> labels = new HashSet<>();
        long seats = 0;
        for (SeatRowRequest row : rows) {
            String label = row.rowLabel().trim();
            if (!labels.add(label)) {
                throw invalid("Row " + label + " appears more than once");
            }
            if (row.toSeat() < row.fromSeat()) {
                throw invalid("Row " + label + ": toSeat must be at least fromSeat");
            }
            seats += (long) row.toSeat() - row.fromSeat() + 1;
            if (seats > MAX_SEATS_PER_REQUEST) {
                throw invalid("A zone can have at most " + MAX_SEATS_PER_REQUEST + " seats per request");
            }
        }
        if (request.capacity() != null && request.capacity() != seats) {
            throw invalid("capacity (" + request.capacity() + ") does not match the " + seats
                    + " seats described by rows; leave it out for a SEATED zone");
        }
        return (int) seats;
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, detail);
    }
}
