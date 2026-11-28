-- Module: common
-- Which run of a request holds the key: new at each claim and takeover, and required to complete or release it, so an
-- earlier run that is still going cannot touch the row of a later one. NULL only for rows written before this column.
ALTER TABLE idempotency_keys
    ADD COLUMN claim_token UUID;
