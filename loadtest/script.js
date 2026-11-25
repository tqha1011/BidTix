// One k6 run of the load test; loadtest/run.sh starts it. Every iteration is exactly one request.
//   PROFILE  read | book | mixed
//   MODE     rate  : RATE requests per second for DURATION seconds, however long the answers take
//            users : USERS users at once, each waiting 1-3 s between requests; they arrive over RAMP seconds, and only
//                    what happens after that, for DURATION seconds, is measured
//   BASE_URL, TOKENS (file made by tokens.sh), SUMMARY (where the result of this run is written)
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';

const PROFILE = __ENV.PROFILE || 'mixed';
const MODE = __ENV.MODE || 'rate';
const BASE_URL = __ENV.BASE_URL;
const DURATION = Number(__ENV.DURATION || 60);
const RATE = Number(__ENV.RATE || 100);
const USERS = Number(__ENV.USERS || 1000);
const RAMP = Number(__ENV.RAMP || 30);

// the pass mark
const MAX_P95_MS = 500;
const MAX_FAILED = 0.01;
const MAX_DROPPED = 0.01;

const EVENTS = 50;
const HOT_ZONE = '00000000-0000-4000-a000-000000000001';
const eventId = (n) => `00000000-0000-4000-9000-${String(n).padStart(12, '0')}`;

if (!['read', 'book', 'mixed'].includes(PROFILE)) {
    throw new Error(`unknown PROFILE ${PROFILE}`);
}

const tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS)));

const scenario = MODE === 'users'
    ? {
        executor: 'ramping-vus',
        startVUs: 0,
        stages: [{ duration: `${RAMP}s`, target: USERS }, { duration: `${DURATION}s`, target: USERS }],
        gracefulRampDown: '0s',
        gracefulStop: '10s',
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
    summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
    // these make k6 keep separate numbers for the measured part of the run
    thresholds: {
        'http_req_duration{phase:measure}': [`p(95)<${MAX_P95_MS}`],
        'http_req_failed{phase:measure}': [`rate<${MAX_FAILED}`],
        'http_reqs{phase:measure}': ['count>0'],
    },
};

function params(token, name, extraHeaders) {
    const ramping = MODE === 'users' && exec.instance.currentTestRunDuration < RAMP * 1000;
    return {
        headers: Object.assign({ Authorization: `Bearer ${token}` }, extraHeaders),
        // "name" keeps one metric per endpoint instead of one per URL
        tags: { name, phase: ramping ? 'ramp' : 'measure' },
        timeout: '10s',
    };
}

/** Half the reads are the event list (cached in Redis), half one event with what is left in its zones (not cached). */
function read(token) {
    if (Math.random() < 0.5) {
        http.get(`${BASE_URL}/api/v1/events?page=0&size=20`, params(token, 'event-list'));
    } else {
        const n = 1 + Math.floor(Math.random() * EVENTS);
        http.get(`${BASE_URL}/api/v1/events/${eventId(n)}`, params(token, 'event-detail'));
    }
}

/** One standing ticket in the hot zone: every booking updates the same counter row. */
function book(token) {
    const body = JSON.stringify({ eventPublicId: eventId(1), zonePublicId: HOT_ZONE, quantity: 1 });
    http.post(`${BASE_URL}/api/v1/orders`, body,
        params(token, 'book', { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }));
}

export default function () {
    const who = MODE === 'users' ? exec.vu.idInTest - 1 : exec.scenario.iterationInTest;
    const token = tokens[who % tokens.length];

    if (PROFILE === 'book' || (PROFILE === 'mixed' && Math.random() < 0.1)) {
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
        target: MODE === 'users' ? USERS : RATE,
        seconds: DURATION,
        requests,
        rps: requests / DURATION,
        p50: value('http_req_duration{phase:measure}', 'med'),
        p95: value('http_req_duration{phase:measure}', 'p(95)'),
        p99: value('http_req_duration{phase:measure}', 'p(99)'),
        // k6 calls this "rate": the share of requests that did not answer 2xx or 3xx
        failed: value('http_req_failed{phase:measure}', 'rate'),
        dropped: dropped + iterations > 0 ? dropped / (dropped + iterations) : 0,
    };
    result.pass = requests > 0 && result.p95 < MAX_P95_MS && result.failed < MAX_FAILED && result.dropped < MAX_DROPPED;
    return {
        [__ENV.SUMMARY]: JSON.stringify(result),
        stdout: `${JSON.stringify(result)}\n`,
    };
}
