package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.AdminTicketResponse;
import com.bidtix.booking.dtos.CheckInResponse;
import com.bidtix.booking.dtos.MyTicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** All VALID tickets of an order -&gt; CANCELLED, in one statement. A USED ticket is not touched: check the row count. */
    @Modifying
    @Query("update Ticket t set t.status = TicketStatus.CANCELLED, t.updatedAt = :now " +
            "where t.order.id = :orderId and t.status = TicketStatus.VALID")
    int cancelValidTickets(@Param("orderId") long orderId, @Param("now") Instant now);

    /**
     * Read model for the admin list. Tickets point at zones, seats and users only by id (other modules), so
     * this joins them with explicit {@code on} clauses and selects straight into the DTO: one query, no N+1.
     * A deliberate shortcut across modules, fine for a read-only admin view. The seat join is a LEFT join
     * because general-admission tickets have no seat. Ordering is fixed (by id), so the caller must pass an
     * unsorted {@code Pageable}.
     */
    @Query(value = """
            select new com.bidtix.booking.dtos.AdminTicketResponse(
                t.publicId, t.status, t.usedAt, z.publicId, z.name, s.rowLabel, s.seatNumber,
                u.publicId, u.email, o.orderCode, t.createdAt)
            from Ticket t
            join t.order o
            join TicketZone z on z.id = t.zoneId
            left join Seat s on s.id = t.seatId
            join User u on u.id = t.ownerId
            where t.eventId = :eventId and t.status in :statuses
            order by t.id
            """,
            countQuery = "select count(t) from Ticket t where t.eventId = :eventId and t.status in :statuses")
    Page<AdminTicketResponse> findAdminViewByEvent(Long eventId, Collection<TicketStatus> statuses, Pageable pageable);

    /** Read model for the owner's own list: one query across the modules, like the admin list. Newest first. */
    @Query(value = """
            select new com.bidtix.booking.dtos.MyTicketResponse(
                t.publicId, t.status, e.publicId, e.title, e.startsAt, z.name, s.rowLabel, s.seatNumber,
                o.orderCode, t.createdAt)
            from Ticket t
            join t.order o
            join Event e on e.id = t.eventId
            join TicketZone z on z.id = t.zoneId
            left join Seat s on s.id = t.seatId
            join User u on u.id = t.ownerId
            where u.publicId = :ownerPublicId
            order by t.id desc
            """,
            countQuery = "select count(t) from Ticket t join User u on u.id = t.ownerId where u.publicId = :ownerPublicId")
    Page<MyTicketResponse> findTicketsOfOwner(UUID ownerPublicId, Pageable pageable);


    @Modifying
    @Query("update Ticket t " +
            "set t.status = TicketStatus.USED, t.usedAt = :now, t.updatedAt = :now " +
            "where t.publicId = :ticketPublicId and t.status = TicketStatus.VALID and t.eventId = :eventId")
    int checkInTicket(@Param("ticketPublicId") UUID ticketPublicId,@Param("eventId") Long eventId ,@Param("now") Instant now);


    Optional<Ticket> findByPublicId(UUID publicId);

    @Query("select new com.bidtix.booking.dtos.CheckInResponse(t.publicId, e.title, z.name, s.rowLabel, s.seatNumber, " +
            "u.fullName, o.orderCode, t.usedAt) " +
            "from Ticket t " +
            "join Event e on e.id = t.eventId " +
            "join TicketZone z on z.id = t.zoneId " +
            "left join Seat s on s.id = t.seatId " +
            "join User u on u.id = t.ownerId " +
            "join Order o on o.id = t.order.id " +
            "where t.publicId = :ticketPublicId and t.eventId = :eventId")
    CheckInResponse checkInTicketResponse(UUID ticketPublicId, Long eventId);
}
