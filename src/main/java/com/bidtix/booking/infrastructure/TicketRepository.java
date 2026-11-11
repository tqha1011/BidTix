package com.bidtix.booking.infrastructure;

import com.bidtix.booking.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
}
