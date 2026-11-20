package com.bidtix.event.infrastructure;

import com.bidtix.event.domain.TicketZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;


public interface TicketZoneRepository extends JpaRepository<TicketZone, Long> {
    Optional<TicketZone> findByPublicId(UUID publicId);

    boolean existsByEventId(Long eventId);

    /**
     * Takes {@code quantity} tickets from a general-admission zone, but only if that many are left. The
     * condition and the subtraction are one statement, so two buyers can never both take the last ones.
     * The caller MUST check the returned row count: 1 = taken, 0 = sold out (or not enough).
     * It is a bulk update, so zone entities loaded earlier are stale afterwards. The counter row stays
     * locked until the transaction ends, so run this as late in the transaction as possible.
     */
    @Modifying
    @Query("update TicketZone z " +
            "set z.availableQuantity = z.availableQuantity - :quantity, z.version = z.version + 1 " +
            "where z.id = :zoneId and z.zoneType = 'GENERAL_ADMISSION' and z.availableQuantity >= :quantity")
    int takeQuantity(Long zoneId, int quantity);

    /** Read straight from the database (a scalar query), so it sees an update made earlier in the same transaction. */
    @Query("select z.availableQuantity from TicketZone z where z.id = :zoneId")
    Integer findAvailableQuantity(Long zoneId);


    /** Gives back {@code quantity} tickets to a general-admission zone. Returns 1 if the zone was updated. */
    @Modifying
    @Query("update TicketZone z " +
            "set z.availableQuantity = z.availableQuantity + :quantity, z.version = z.version + 1 " +
            "where z.id = :zoneId and z.zoneType = 'GENERAL_ADMISSION'")
    int refundQuantity(Long zoneId, int quantity);

    @Query("select z.publicId from TicketZone z where z.id = :id")
    Optional<UUID> findPublicIdByid(Long id);
}
