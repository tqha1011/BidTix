-- Module: event
-- Ticket zones are shown to users, so they get a public UUID like the other API-facing tables.
-- gen_random_uuid() is volatile, so PostgreSQL evaluates it once per row: existing zones each get
-- their own distinct value, and the NOT NULL + UNIQUE constraints are safe to add in the same step.
ALTER TABLE ticket_zones
    ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE ticket_zones
    ADD CONSTRAINT uq_ticket_zones_public_id UNIQUE (public_id);
