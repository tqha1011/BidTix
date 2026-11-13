package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Ticket;
import com.bidtix.booking.domain.TicketStatus;
import com.bidtix.booking.dtos.AdminTicketResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

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
}
