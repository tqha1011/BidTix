-- Data for the load test, on a database Flyway has just built. Run by loadtest/run.sh:
--   psql -v users=5000 -f seed.sql
-- Public ids are derived from a number, so tokens.sh and script.js can name rows without reading them back:
--   customer i : 00000000-0000-4000-8000-<i, 12 digits>
--   event n    : 00000000-0000-4000-9000-<n, 12 digits>
--   hot zone   : 00000000-0000-4000-a000-000000000001   (standing, in event 1, never sells out during a test)
\set ON_ERROR_STOP on
BEGIN;

INSERT INTO users (public_id, email, password_hash, full_name, role)
SELECT ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
       'load' || i || '@loadtest.invalid', 'no-login', 'Load User ' || i, 'CUSTOMER'
FROM generate_series(1, :users) AS i;

INSERT INTO users (email, password_hash, full_name, role)
VALUES ('organizer@loadtest.invalid', 'no-login', 'Load Organizer', 'ORGANIZER');

INSERT INTO venues (name, address, city)
VALUES ('Load Test Arena', '1 Test Street', 'Test City');

INSERT INTO events (public_id, organizer_id, venue_id, title, description, starts_at, ends_at,
                    sale_starts_at, sale_ends_at, status)
SELECT ('00000000-0000-4000-9000-' || lpad(n::text, 12, '0'))::uuid,
       (SELECT id FROM users WHERE email = 'organizer@loadtest.invalid'),
       (SELECT id FROM venues WHERE name = 'Load Test Arena'),
       'Load Test Event ' || n, 'Created by loadtest/seed.sql',
       now() + interval '30 days' + n * interval '1 minute',
       now() + interval '31 days' + n * interval '1 minute',
       now() - interval '1 day', now() + interval '29 days', 'PUBLISHED'
FROM generate_series(1, 50) AS n;

-- every event: two standing zones and one seated zone of 100 seats, so that reading an event has real work to do
INSERT INTO ticket_zones (event_id, name, zone_type, price, capacity, available_quantity)
SELECT e.id, z.name, 'GENERAL_ADMISSION', z.price, 1000, 1000
FROM events e CROSS JOIN (VALUES ('Standing', 400000), ('Lawn', 250000)) AS z(name, price);

INSERT INTO ticket_zones (event_id, name, zone_type, price, capacity)
SELECT id, 'Seated', 'SEATED', 900000, 100 FROM events;

INSERT INTO seats (zone_id, row_label, seat_number)
SELECT z.id, r.label, s.number
FROM ticket_zones z
         CROSS JOIN (VALUES ('A'), ('B'), ('C'), ('D'), ('E')) AS r(label)
         CROSS JOIN generate_series(1, 20) AS s(number)
WHERE z.zone_type = 'SEATED';

INSERT INTO ticket_zones (public_id, event_id, name, zone_type, price, capacity, available_quantity)
SELECT '00000000-0000-4000-a000-000000000001', id, 'Hot', 'GENERAL_ADMISSION', 500000, 10000000, 10000000
FROM events
WHERE public_id = '00000000-0000-4000-9000-000000000001';

COMMIT;
ANALYZE;
