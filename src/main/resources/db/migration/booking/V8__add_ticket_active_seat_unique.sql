-- Module: booking
-- Last line of defence against double-selling a seat: at most one non-cancelled ticket per seat.
-- Partial, so a seat whose ticket was cancelled (refunded) can be sold again.
CREATE UNIQUE INDEX ux_tickets_active_seat
    ON tickets (seat_id)
    WHERE seat_id IS NOT NULL AND status <> 'CANCELLED';
