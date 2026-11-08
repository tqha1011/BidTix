-- Module: event
-- Seats are shown on the seat map and chosen by users, so they get a public UUID too.
-- gen_random_uuid() is volatile, so PostgreSQL evaluates it once per row: every existing seat gets
-- its own distinct value, and NOT NULL + UNIQUE are safe to add in the same step.
ALTER TABLE seats
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE seats
    ADD CONSTRAINT uq_seats_public_id UNIQUE (public_id);
