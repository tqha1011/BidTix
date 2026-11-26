-- Data for the "sale day" run (soak.sh): one hot concert whose sale opens a few minutes into the test, and nineteen
-- quieter events already on sale. Auctions are created by realistic.js through the API.
--   psql -v users=5000 -v open_in=180 -f seed-realistic.sql
--   customer i : load<i>@loadtest.invalid      organizer : organizer@loadtest.invalid      password : LoadTest123!
--   event n    : 00000000-0000-4000-9000-<n, 12 digits>, n = 1 is the hot concert
\set ON_ERROR_STOP on
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- BCrypt with the strength Spring uses (10). One hash for everybody: checking it costs the application the same.
CREATE TEMPORARY TABLE secret ON COMMIT DROP AS SELECT crypt('LoadTest123!', gen_salt('bf', 10)) AS hash;

INSERT INTO users (public_id, email, password_hash, full_name, role)
SELECT ('00000000-0000-4000-8000-' || lpad(i::text, 12, '0'))::uuid,
       'load' || i || '@loadtest.invalid', secret.hash, 'Load User ' || i, 'CUSTOMER'
FROM generate_series(1, :users) AS i, secret;

INSERT INTO users (email, password_hash, full_name, role)
SELECT 'organizer@loadtest.invalid', hash, 'Load Organizer', 'ORGANIZER' FROM secret;

INSERT INTO venues (name, address, city)
VALUES ('Load Test Arena', '1 Test Street', 'Test City');

INSERT INTO events (public_id, organizer_id, venue_id, title, description, starts_at, ends_at,
                    sale_starts_at, sale_ends_at, status)
SELECT ('00000000-0000-4000-9000-' || lpad(n::text, 12, '0'))::uuid,
       (SELECT id FROM users WHERE email = 'organizer@loadtest.invalid'),
       (SELECT id FROM venues WHERE name = 'Load Test Arena'),
       CASE WHEN n = 1 THEN 'Hot Concert' ELSE 'Event ' || n END, 'Created by loadtest/seed-realistic.sql',
       now() + interval '30 days' + n * interval '1 minute',
       now() + interval '31 days' + n * interval '1 minute',
       CASE WHEN n = 1 THEN now() + make_interval(secs => :open_in) ELSE now() - interval '1 day' END,
       now() + interval '29 days', 'PUBLISHED'
FROM generate_series(1, 20) AS n;

-- the hot concert: 4,000 tickets (VIP 200 seats, Seated 800 seats, Standing 3,000), few enough to sell out
INSERT INTO ticket_zones (event_id, name, zone_type, price, capacity, available_quantity)
SELECT e.id, z.name, z.zone_type, z.price, z.capacity,
       CASE WHEN z.zone_type = 'GENERAL_ADMISSION' THEN z.capacity END
FROM events e
         CROSS JOIN (VALUES ('VIP', 'SEATED', 2500000, 200), ('Seated', 'SEATED', 1200000, 800),
                            ('Standing', 'GENERAL_ADMISSION', 600000, 3000)) AS z(name, zone_type, price, capacity)
WHERE e.public_id = '00000000-0000-4000-9000-000000000001';

-- the others: 2,000 standing and 200 seats each
INSERT INTO ticket_zones (event_id, name, zone_type, price, capacity, available_quantity)
SELECT e.id, z.name, z.zone_type, z.price, z.capacity,
       CASE WHEN z.zone_type = 'GENERAL_ADMISSION' THEN z.capacity END
FROM events e
         CROSS JOIN (VALUES ('Seated', 'SEATED', 900000, 200),
                            ('Standing', 'GENERAL_ADMISSION', 400000, 2000)) AS z(name, zone_type, price, capacity)
WHERE e.public_id <> '00000000-0000-4000-9000-000000000001';

-- twenty seats to a row
INSERT INTO seats (zone_id, row_label, seat_number)
SELECT z.id, 'R' || lpad((((s - 1) / 20) + 1)::text, 2, '0'), ((s - 1) % 20) + 1
FROM ticket_zones z
         CROSS JOIN LATERAL generate_series(1, z.capacity) AS s
WHERE z.zone_type = 'SEATED';

COMMIT;
ANALYZE;
