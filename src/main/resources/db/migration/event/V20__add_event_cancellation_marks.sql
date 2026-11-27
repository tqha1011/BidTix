-- Module: event
-- Cancelling an event is cleaned up afterwards by two jobs. cancelled_at is when the event became CANCELLED. Each of the
-- other two is set by one module once it has nothing left to do for that event, so its job stops looking at it:
-- auctions_cleaned_at by the auction module, orders_cleaned_at by the booking module. NULL means "not done yet".
ALTER TABLE events
    ADD COLUMN cancelled_at        TIMESTAMPTZ,
    ADD COLUMN auctions_cleaned_at TIMESTAMPTZ,
    ADD COLUMN orders_cleaned_at   TIMESTAMPTZ;
