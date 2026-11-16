-- Module: event
-- The public event list (GET /api/v1/events) filters on status = 'PUBLISHED' and starts_at > now, and sorts by
-- starts_at. This was listed under "Deferred" in docs/database-schema.md until that query existed.
CREATE INDEX idx_events_status_starts_at ON events (status, starts_at);
