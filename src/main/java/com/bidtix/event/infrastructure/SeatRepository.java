package com.bidtix.event.infrastructure;

import com.bidtix.event.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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


    /** Seats that these orders hold, with their zone, so the booking gate flags of the zone can be cleared. */
    @Query("select s.publicId as seatPublicId, s.zone.publicId as zonePublicId from Seat s " +
            "where s.heldByOrderId in :orderIds and s.status = 'HELD'")
    List<HeldSeat> findHeldByOrderIds(List<Long> orderIds);

    /**
     * Gives back every seat these orders hold (HELD -> AVAILABLE). Only HELD seats change, so a seat that was
     * already sold or released is never touched. Returns the number of seats released.
     */
    @Modifying
    @Query("update Seat s " +
            "set s.status = 'AVAILABLE', s.heldByOrderId = null, s.holdExpiresAt = null, s.version = s.version + 1 " +
            "where s.heldByOrderId in :orderIds and s.status = 'HELD'")
    int releaseSeatsHeldByOrders(List<Long> orderIds);

    /** A seat of the given zone; a seat named with the wrong zone is "not found", like zones of another event. */
    Optional<Seat> findByPublicIdAndZoneId(UUID publicId, Long zoneId);

    /**
     * Takes a free seat out of normal sale because it is being auctioned (AVAILABLE -&gt; RESERVED_FOR_AUCTION). The
     * condition and the change are one statement, so a booking holding the seat and an auction cannot both get it.
     * The caller MUST check the returned row count: 1 = reserved, 0 = the seat is not free any more.
     */
    @Modifying
    @Query("update Seat s " +
            "set s.status = 'RESERVED_FOR_AUCTION', s.version = s.version + 1 " +
            "where s.id = :seatId and s.status = 'AVAILABLE' and s.heldByOrderId is null")
    int reserveForAuction(Long seatId);

    interface HeldSeat {
        UUID getSeatPublicId();

        UUID getZonePublicId();
    }
}
