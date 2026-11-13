package com.bidtix.event.infrastructure;

import com.bidtix.event.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, Long> {


    @Query("select s " +
            "from Seat s " +
            "where s.status = 'AVAILABLE' and s.zone.id = :zoneId " +
            "and s.heldByOrderId is null " +
            "order by s.id")
    List<Seat> findAllAvailableSeatByTicketZoneId(Long zoneId);

    /**
     * Conditionally holds the seats for an order. Only seats that are still AVAILABLE are updated,
     * so the caller MUST compare the returned row count with {@code seatIds.size()}.
     * {@code hold_expires_at} is required by the DB check {@code ck_seats_hold_consistency}.
     */
    @Modifying
    @Query("update Seat s " +
            "set s.status = 'HELD', s.heldByOrderId = :orderId, s.holdExpiresAt = :holdExpiresAt, " +
            "s.version = s.version + 1 " +
            "where s.id IN :seatIds and s.status = 'AVAILABLE' and s.heldByOrderId is null ")
    int updateSeatHoldBySeatIds(List<Long> seatIds, Long orderId, Instant holdExpiresAt);

    /**
     * HELD -> SOLD for every seat the order holds. The caller MUST compare the returned row count with
     * the number of seated items. SOLD seats keep no hold data (see {@code ck_seats_hold_consistency}).
     */
    @Modifying
    @Query("update Seat s " +
            "set s.status = 'SOLD', s.heldByOrderId = null, s.holdExpiresAt = null, s.version = s.version + 1 " +
            "where s.heldByOrderId = :orderId and s.status = 'HELD'")
    int markSeatsSoldByOrderId(Long orderId);

    /** Public ids of the seats an order currently holds (read from the DB, not the persistence context). */
    @Query("select s.publicId from Seat s where s.heldByOrderId = :orderId")
    Set<UUID> findPublicIdsHeldByOrderId(Long orderId);
}
