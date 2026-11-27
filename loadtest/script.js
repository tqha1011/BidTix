// One k6 run of the load test; loadtest/run.sh starts it. Every iteration is exactly one request.
//   PROFILE  read | book | mixed (BOOK_SHARE of the requests are bookings, 0.1 unless said otherwise)
//   SEATED_SHARE  of the bookings, the share that takes one numbered seat of the hall instead of a standing ticket
//            (0 unless said otherwise); SEAT_BASE is the first seat that no earlier run on this database has taken
//   MODE     rate  : RATE requests per second for DURATION seconds, however long the answers take
//            users : USERS users at once, each waiting 1-3 s between requests; they arrive over RAMP seconds, and only
//                    what happens after that, for DURATION seconds, is measured
//            concurrency : CONCURRENCY clients that send the next request as soon as the answer arrives, so that many
//                    requests are open at any moment; arriving over RAMP seconds like the users
//   BASE_URL, TOKENS (file made by tokens.sh), SUMMARY (where the result of this run is written)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

const PROFILE = __ENV.PROFILE || 'mixed';
const MODE = __ENV.MODE || 'rate';
const BASE_URL = __ENV.BASE_URL;
const DURATION = Number(__ENV.DURATION || 60);
const RATE = Number(__ENV.RATE || 100);
const USERS = Number(__ENV.USERS || 1000);
const RAMP = Number(__ENV.RAMP || 30);
const CONCURRENCY = Number(__ENV.CONCURRENCY || 10);
const BOOK_SHARE = Number(__ENV.BOOK_SHARE || 0.1);
const SEATED_SHARE = Number(__ENV.SEATED_SHARE || 0);
const SEAT_BASE = Number(__ENV.SEAT_BASE || 0);
// arrive gradually, then hold: both closed modes
const CLOSED = MODE === 'users' || MODE === 'concurrency';
const CLIENTS = MODE === 'concurrency' ? CONCURRENCY : USERS;

const timeouts = new Counter('timeouts');

// the pass mark
const MAX_P95_MS = 500;
const MAX_FAILED = 0.01;
const MAX_DROPPED = 0.01;

const EVENTS = 50;
const HOT_ZONE = '00000000-0000-4000-a000-000000000001';
const eventId = (n) => `00000000-0000-4000-9000-${String(n).padStart(12, '0')}`;
const HALL_EVENT = 51;
const hallBlock = (seat) => `00000000-0000-4000-a001-${String(Math.floor(seat / 1000)).padStart(12, '0')}`;
const seatId = (n) => `00000000-0000-4000-b000-${String(n).padStart(12, '0')}`;

if (!['read', 'book', 'mixed'].includes(PROFILE)) {
    throw new Error(`unknown PROFILE ${PROFILE}`);
}

const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS)));

const scenario = CLOSED
    ? {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [{ duration: `${RAMP}s`, target: CLIENTS }, { duration: `${DURATION}s`, target: CLIENTS }],
        gracefulRampDown: '0s',
        // longer than the request timeout, so every request that was sent is also counted
        gracefulStop: '15s',
    }
    : {
        executor: 'constant-arrival-rate',
        rate: RATE,
        timeUnit: '1s',
        duration: `${DURATION}s`,
        preAllocatedVUs: Math.min(1000, Math.max(50, RATE)),
        maxVUs: 3000,
        gracefulStop: '10s',
    };

export const options = {
    scenarios: { load: scenario },
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // these make k6 keep separate numbers for the measured part of the run
    thresholds: {
        'http_req_duration{phase:measure}': [`p(95)<${MAX_P95_MS}`],
        'http_req_failed{phase:measure}': [`rate<${MAX_FAILED}`],
        'http_reqs{phase:measure}': ['count>0'],
        // always pass: they only make k6 report reads, bookings and timeouts on their own
        'http_req_duration{kind:read,phase:measure}': ['max>=0'],
        'http_req_duration{kind:book,phase:measure}': ['max>=0'],
        'http_req_duration{stock:seat,phase:measure}': ['max>=0'],
        'http_req_duration{stock:standing,phase:measure}': ['max>=0'],
        'http_reqs{stock:seat,phase:measure}': ['count>=0'],
        'http_reqs{stock:standing,phase:measure}': ['count>=0'],
        'timeouts{phase:measure}': ['count>=0'],
    },
};

function params(token, name, extraHeaders) {
    const ramping = CLOSED && exec.instance.currentTestRunDuration < RAMP * 1000;
    return {
        headers: Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders),
        // "name" keeps one metric per endpoint instead of one per URL
        tags: { name, kind: name === 'book' ? 'book' : 'read', phase: ramping ? 'ramp' : 'measure' },
        timeout: '10s',
    };
}

/** Counts the answers that never came within the timeout, apart from the other failures. */
function note(response, sent) {
    if (response.error_code === 1050) timeouts.add(1, { phase: sent.tags.phase });
}

/** Half the reads are the event list (cached in Redis), half one event with what is left in its zones (not cached). */
function read(token) {
    if (Math.random() < 0.5) {
        const sent = params(token, 'event-list');
        note(http.get(`${BASE_URL}/api/v1/events?page=0&size=20`, sent), sent);
    } else {
        const n = 1 + Math.floor(Math.random() * EVENTS);
        const sent = params(token, 'event-detail');
        note(http.get(`${BASE_URL}/api/v1/events/${eventId(n)}`, sent), sent);
    }
}

/** One order. "stock" keeps seat and standing bookings apart in the results. */
function order(token, stock, what) {
    const sent = params(token, 'book', { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() });
    sent.tags.stock = stock;
    note(http.post(`${BASE_URL}/api/v1/orders`, JSON.stringify(what), sent), sent);
}

// every virtual user has its own copy of this
let seatsAsked = 0;

/** A seat nobody else asks for: user v of n takes seats v, v + n, v + 2n, ... after SEAT_BASE. */
function nextSeat() {
    if (!CLOSED) return SEAT_BASE + exec.scenario.iterationInTest;
    return SEAT_BASE + seatsAsked++ * CLIENTS + exec.vu.idInTest - 1;
}

/**
 * One numbered seat of the hall, or one standing ticket in the hot zone. Every standing booking updates the same
 * counter row; seat bookings update different rows.
 */
function book(token) {
    if (Math.random() < SEATED_SHARE) {
        const seat = nextSeat();
        order(token, 'seat',
            { eventPublicId: eventId(HALL_EVENT), zonePublicId: hallBlock(seat), seatPublicIds: [seatId(seat)] });
    } else {
        order(token, 'standing', { eventPublicId: eventId(1), zonePublicId: HOT_ZONE, quantity: 1 });
    }
}

export default function () {
    const who = CLOSED ? exec.vu.idInTest - 1 : exec.scenario.iterationInTest;
    const token = tokens[who % tokens.length];

    if (PROFILE === 'book' || (PROFILE === 'mixed' && Math.random() < BOOK_SHARE)) {
        book(token);
    } else {
        read(token);
    }

    if (MODE === 'users') {
        sleep(1 + Math.random() * 2);
    }
}

export function handleSummary(data) {
    const value = (metric, key) => (data.metrics[metric] ? data.metrics[metric].values[key] : 0) || 0;
    const requests = value('http_reqs{phase:measure}', 'count');
    const dropped = value('dropped_iterations', 'count');
    const iterations = value('iterations', 'count');
    const result = {
        profile: PROFILE,
        mode: MODE,
        target: CLOSED ? CLIENTS : RATE,
        seconds: DURATION,
        requests,
        rps: requests / DURATION,
        avg: value('http_req_duration{phase:measure}', 'avg'),
        p50: value('http_req_duration{phase:measure}', 'med'),
        p95: value('http_req_duration{phase:measure}', 'p(95)'),
        p99: value('http_req_duration{phase:measure}', 'p(99)'),
        readP95: value('http_req_duration{kind:read,phase:measure}', 'p(95)'),
        bookP95: value('http_req_duration{kind:book,phase:measure}', 'p(95)'),
        seatBookings: value('http_reqs{stock:seat,phase:measure}', 'count'),
        seatP95: value('http_req_duration{stock:seat,phase:measure}', 'p(95)'),
        standingBookings: value('http_reqs{stock:standing,phase:measure}', 'count'),
        standingP95: value('http_req_duration{stock:standing,phase:measure}', 'p(95)'),
        timeouts: value('timeouts{phase:measure}', 'count'),
        // k6 calls this "rate": the share of requests that did not answer 2xx or 3xx
        failed: value('http_req_failed{phase:measure}', 'rate'),
        dropped: dropped + iterations > 0 ? dropped / (dropped + iterations) : 0,
    };
    result.pass = requests > 0 && result.p95 < MAX_P95_MS && result.failed < MAX_FAILED && result.dropped < MAX_DROPPED;
    // Little's law: requests open at once = rate x average time each one stays open. In the concurrency mode this has
    // to come out close to CONCURRENCY, which checks the measurement itself.
    result.open = result.rps * result.avg / 1000;
    result.broken = requests === 0 || result.failed >= MAX_FAILED;
    return {
        [__ENV.SUMMARY]: JSON.stringify(result),
        stdout: `${JSON.stringify(result)}\n`,
    };
}
