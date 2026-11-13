-- Module: event
-- Venues are chosen by organizers when they create an event, so they get a public UUID like the other
-- API-facing tables. gen_random_uuid() is volatile, so PostgreSQL evaluates it once per row: every
-- existing venue gets its own distinct value, and NOT NULL + UNIQUE are safe to add in the same step.
ALTER TABLE venues
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE venues
    ADD CONSTRAINT uq_venues_public_id UNIQUE (public_id);
