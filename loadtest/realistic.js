// "Sale day": people behaving like buyers and bidders for MINUTES minutes. Started by soak.sh.
//
// Shoppers (a crowd that grows, spikes when the hot concert opens, then stays): log in, browse, most leave, the rest
// pick seats or standing tickets, book, pay (or give up, or get declined), then look at their tickets.
// Bidders (a steady group): watch an auction, outbid each other up to a budget, pay when they win.
// "VNPAY" is played by this script: it signs the callback with the load test's own secret and calls the IPN.
//
//   BASE_URL SUMMARY MINUTES BASE PEAK BIDDERS USERS
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { hmac } from 'k6/crypto';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL;
const MINUTES = Number(__ENV.MINUTES || 30);
const BASE = Number(__ENV.BASE || 300);
const PEAK = Number(__ENV.PEAK || 1000);
const BIDDERS = Number(__ENV.BIDDERS || 150);
const PASSWORD = 'LoadTest123!';
const SECRET = 'loadtest-only-secret';
const SECONDS = Math.round(MINUTES * 60);
// the sale of the hot concert opens a tenth into the run (soak.sh seeds the same moment)
const OPENS = Math.round(SECONDS * 0.1);
const SPIKE = Math.max(30, Math.round(SECONDS / 30));

const eventId = (n) => `00000000-0000-4000-9000-${String(n).padStart(12, '0')}`;
const HOT = eventId(1);

const at = (fraction) => Math.round(SECONDS * fraction);
const stage = (from, to, target) => ({ duration: `${to - from}s`, target });

const NAMES = ['login', 'refresh', 'event-list', 'event-detail', 'seat-map', 'book', 'pay', 'ipn', 'return', 'order',
    'orders', 'tickets', 'auctions', 'auction', 'leaderboard', 'bid-history', 'bid'];

const thresholds = {};
// thresholds that always pass: they only make k6 keep separate numbers per minute and per endpoint
for (let minute = 0; minute <= Math.ceil(MINUTES); minute++) {
    thresholds[`http_req_duration{minute:${minute}}`] = ['max>=0'];
    thresholds[`http_req_failed{minute:${minute}}`] = ['rate>=0'];
}
for (const name of NAMES) {
    thresholds[`http_req_duration{name:${name}}`] = ['max>=0'];
    thresholds[`http_req_failed{name:${name}}`] = ['rate>=0'];
}

export const options = {
    scenarios: {
        shoppers: {
            executor: 'ramping-vus',
            exec: 'shopper',
            startVUs: 0,
            stages: [
                stage(0, at(0.07), BASE),                     // people arrive
                stage(at(0.07), OPENS, BASE),                 // waiting for the sale
                stage(OPENS, OPENS + SPIKE, PEAK),            // it opens
                stage(OPENS + SPIKE, at(0.33), PEAK),         // the rush
                stage(at(0.33), at(0.36), BASE),              // it calms down
                stage(at(0.36), at(0.93), BASE),              // the long tail
                stage(at(0.93), SECONDS, 0),
            ],
            gracefulRampDown: '30s',
            gracefulStop: '30s',
        },
        bidders: {
            executor: 'constant-vus',
            exec: 'bidder',
            vus: BIDDERS,
            startTime: `${Math.min(30, at(0.05))}s`,
            duration: `${SECONDS - Math.min(30, at(0.05))}s`,
            gracefulStop: '30s',
        },
    },
    // a person keeps their refresh cookie between visits
    noCookiesReset: true,
    summaryTrendStats: ['count', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds,
};

// ---- what happened, in business terms ----------------------------------------------------------------------------
const count = {};
for (const name of ['unexpected', 'bookings', 'seat_conflicts', 'sold_out', 'not_on_sale', 'abandoned',
    'payments_ok', 'payments_declined', 'duplicate_ipn_answered_02', 'paid_but_not_applied', 'bids_accepted',
    'bids_outbid', 'bids_too_late', 'auction_orders_paid', 'auction_orders_ignored']) {
    count[name] = new Counter(name);
}

// ---- state of one simulated person (k6 keeps module variables per VU) --------------------------------------------
let token = null;
let tokenExpires = 0;
let transactions = 0;
let complaints = 0;
const budgets = {};
const myLastBid = {};
const decidedOrders = {};

const between = (low, high) => low + Math.random() * (high - low);
const think = (low, high) => sleep(between(low, high));
const chance = (p) => Math.random() < p;
const minuteNow = () => String(Math.floor(exec.instance.currentTestRunDuration / 60000));

/**
 * One request. {@code expected} are the statuses a healthy application may answer here (a 409 for a seat somebody
 * else took is one); anything else counts as a failure.
 */
function call(name, method, path, { body = null, expected = [200], headers = {}, parse = false, auth = true } = {}) {
    const all = Object.assign({}, headers);
    if (auth) all.Authorization = `Bearer ${token}`;
    if (body !== null) all['Content-Type'] = 'application/json';
    const response = http.request(method, BASE_URL + path, body === null ? null : JSON.stringify(body), {
        headers: all,
        tags: { name, minute: minuteNow() },
        responseCallback: http.expectedStatuses(...expected),
        responseType: parse ? 'text' : 'none',
        timeout: '10s',
    });
    let json = null;
    if (parse && response.body) {
        try {
            json = JSON.parse(response.body);
        } catch (e) {
            json = null;
        }
    }
    if (!expected.includes(response.status)) {
        count.unexpected.add(1, { name });
        if (complaints++ < 2) {
            console.warn(`${name}: ${response.status} ${response.error || ''} ${(response.body || '').slice(0, 200)}`);
        }
    }
    return { status: response.status, json };
}

const key = () => ({ 'Idempotency-Key': crypto.randomUUID() });

/** Logs in on the first visit, afterwards trades the refresh cookie for a new access token when it runs out. */
function signedIn() {
    const now = Date.now();
    if (token && now < tokenExpires) return true;
    let answer = null;
    if (token) answer = call('refresh', 'POST', '/api/v1/auth/refresh', { auth: false, parse: true });
    if (!answer || answer.status !== 200) {
        answer = call('login', 'POST', '/api/v1/auth/login', {
            auth: false, parse: true,
            body: { email: `load${exec.vu.idInTest}@loadtest.invalid`, password: PASSWORD },
        });
    }
    if (answer.status !== 200 || !answer.json) {
        token = null;
        return false;
    }
    token = answer.json.accessToken;
    tokenExpires = now + (answer.json.expiresIn - 30) * 1000;
    return true;
}

// ---- payment: the customer, and VNPAY ----------------------------------------------------------------------------

const encode = (v) => encodeURIComponent(v)
    .replace(/[!'()~]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%20/g, '+');

function queryOf(url) {
    const out = {};
    for (const pair of url.split('?')[1].split('&')) {
        const [name, value] = pair.split('=');
        out[decodeURIComponent(name)] = decodeURIComponent((value || '').replace(/\+/g, ' '));
    }
    return out;
}

/** The callback VNPAY would send for this payment page, signed the way VnpaySigner checks it. */
function vnpayCallback(paymentUrl, success) {
    const asked = queryOf(paymentUrl);
    const params = {
        vnp_TmnCode: asked.vnp_TmnCode,
        vnp_Amount: asked.vnp_Amount,
        vnp_BankCode: 'NCB',
        vnp_CardType: 'ATM',
        vnp_OrderInfo: asked.vnp_OrderInfo,
        vnp_PayDate: new Date(Date.now() + 7 * 3600 * 1000).toISOString().replace(/[-T:]/g, '').slice(0, 14),
        vnp_TransactionNo: success ? `${exec.vu.idInTest}${String(++transactions).padStart(6, '0')}` : '0',
        vnp_ResponseCode: success ? '00' : '24',
        vnp_TransactionStatus: success ? '00' : '02',
        vnp_TxnRef: asked.vnp_TxnRef,
    };
    const text = Object.keys(params).sort().map((name) => `${encode(name)}=${encode(params[name])}`).join('&');
    return `${text}&vnp_SecureHash=${hmac('sha512', SECRET, text, 'hex')}`;
}

/** Pays one order the way a browser and VNPAY would. */
function pay(orderCode) {
    const started = call('pay', 'POST', `/api/v1/orders/${orderCode}/payments`,
        { headers: key(), expected: [201, 409], parse: true });
    if (started.status !== 201) return false;
    think(8, 30);                                       // on VNPAY's page
    const success = chance(0.9);
    const callback = vnpayCallback(started.json.paymentUrl, success);
    const ipn = () => call('ipn', 'GET', `/api/v1/payments/vnpay/ipn?${callback}`, { auth: false, parse: true });

    const answer = ipn();
    const code = answer.json ? answer.json.RspCode : null;
    if (code !== '00') {
        count.unexpected.add(1, { name: 'ipn' });
        if (complaints++ < 2) console.warn(`ipn answered RspCode ${code}`);
        return false;
    }
    if (chance(0.05)) {                                 // VNPAY sends it again
        const again = ipn();
        if (again.json && again.json.RspCode === '02') count.duplicate_ipn_answered_02.add(1);
        else count.unexpected.add(1, { name: 'ipn' });
    }
    if (!signedIn()) return false;                      // the access token may have expired meanwhile
    call('return', 'GET', `/api/v1/payments/vnpay/return?${callback}`, { auth: false });
    think(0.5, 1.5);
    const order = call('order', 'GET', `/api/v1/orders/${orderCode}`, { parse: true });
    if (!success) {
        count.payments_declined.add(1);
        return false;
    }
    if (order.json && order.json.order.status === 'PAID') count.payments_ok.add(1);
    else count.paid_but_not_applied.add(1);
    call('tickets', 'GET', '/api/v1/tickets');
    return true;
}

// ---- shoppers ----------------------------------------------------------------------------------------------------

function chooseTickets(event, zone) {
    if (zone.zoneType !== 'SEATED') {
        const r = Math.random();
        return { eventPublicId: event, zonePublicId: zone.publicId, quantity: r < 0.45 ? 1 : r < 0.85 ? 2 : r < 0.95 ? 3 : 4 };
    }
    const map = call('seat-map', 'GET', `/api/v1/events/${event}/zones/${zone.publicId}/seats`, { parse: true });
    const free = (map.json || []).filter((seat) => seat.status === 'AVAILABLE');
    if (!free.length) return null;
    think(3, 10);                                       // looking at the seat map
    // front rows are wanted most, so people collide there
    const first = Math.floor(Math.random() * Math.random() * free.length);
    const wanted = chance(0.55) ? 2 : 1;
    return {
        eventPublicId: event, zonePublicId: zone.publicId,
        seatPublicIds: free.slice(first, first + wanted).map((seat) => seat.publicId),
    };
}

export function shopper() {
    if (!signedIn()) {
        think(3, 8);
        return;
    }
    call('event-list', 'GET', '/api/v1/events?page=0&size=20');
    think(2, 6);
    const event = chance(0.7) ? HOT : eventId(2 + Math.floor(Math.random() * 19));
    const detail = call('event-detail', 'GET', `/api/v1/events/${event}`, { parse: true });
    if (detail.status !== 200) return;
    think(3, 8);

    if (chance(0.45)) {                                 // only looking
        think(5, 15);
        return;
    }
    const open = detail.json.zones.filter((zone) => zone.available > 0);
    if (!open.length) {
        count.sold_out.add(1);
        think(5, 15);
        return;
    }

    let orderCode = null;
    for (let attempt = 0; attempt < 3 && !orderCode; attempt++) {
        if (!signedIn()) return;
        const body = chooseTickets(event, open[Math.floor(Math.random() * open.length)]);
        if (!body) {
            count.sold_out.add(1);
            break;
        }
        if (body.quantity) think(2, 6);
        const booked = call('book', 'POST', '/api/v1/orders',
            { body, headers: key(), expected: [201, 409], parse: true });
        const code = booked.json ? booked.json.code : null;
        if (booked.status === 201) {
            orderCode = booked.json.orderCode;
            count.bookings.add(1);
        } else if (code === 'SEAT_UNAVAILABLE') {
            count.seat_conflicts.add(1);
            think(1, 4);
        } else if (code === 'ZONE_SOLD_OUT') {
            count.sold_out.add(1);
            break;
        } else if (code === 'EVENT_NOT_ON_SALE') {      // waiting for the sale to open
            count.not_on_sale.add(1);
            think(3, 10);
            break;
        } else {
            break;
        }
    }
    if (!orderCode) {
        think(3, 10);
        return;
    }

    think(2, 8);                                        // checking the order before paying
    if (chance(0.15)) {                                 // walks away; the order has to expire by itself
        count.abandoned.add(1);
    } else if (signedIn() && !pay(orderCode) && chance(0.3) && signedIn()) {
        pay(orderCode);                                 // tries once more
    }
    think(5, 20);
}

// ---- bidders -----------------------------------------------------------------------------------------------------

/** A winner (or the next in line, when a winner did not pay) finds an unpaid auction order among their orders. */
function payWonAuctions() {
    if (!signedIn()) return;
    const orders = call('orders', 'GET', '/api/v1/orders?page=0&size=20', { parse: true });
    for (const order of (orders.json ? orders.json.items : [])) {
        if (order.source !== 'AUCTION' || order.status !== 'PENDING_PAYMENT' || decidedOrders[order.orderCode]) continue;
        decidedOrders[order.orderCode] = true;
        if (chance(0.7)) {
            think(3, 15);
            if (signedIn() && pay(order.orderCode)) count.auction_orders_paid.add(1);
        } else {
            count.auction_orders_ignored.add(1);        // the right to buy has to pass on
        }
    }
}

export function bidder() {
    if (!signedIn()) {
        think(3, 8);
        return;
    }
    const list = call('auctions', 'GET', '/api/v1/auctions?page=0&size=20', { parse: true });
    const open = list.json ? list.json.items : [];
    if (!open.length) {
        payWonAuctions();
        think(8, 15);
        return;
    }
    // the ones about to end draw the most people
    const chosen = open[Math.floor(Math.random() * Math.random() * open.length)].publicId;

    for (let look = 0; look < 20; look++) {
        if (!signedIn()) return;
        const answer = call('auction', 'GET', `/api/v1/auctions/${chosen}`, { parse: true });
        const auction = answer.json;
        if (!auction || (auction.status !== 'LIVE' && auction.status !== 'SCHEDULED')) break;
        if (look % 3 === 0) call('leaderboard', 'GET', `/api/v1/auctions/${chosen}/leaderboard`);
        if (look % 7 === 3) call('bid-history', 'GET', `/api/v1/auctions/${chosen}/bids?page=0&size=20`);

        const left = (Date.parse(auction.endsAt) - Date.now()) / 1000;
        if (budgets[chosen] === undefined) {
            budgets[chosen] = auction.startPrice + auction.minIncrement * Math.floor(between(5, 400));
        }
        const minimum = auction.currentPrice === null || auction.currentPrice === undefined
            ? auction.startPrice : auction.currentPrice + auction.minIncrement;
        const leading = myLastBid[chosen] !== undefined && myLastBid[chosen] === auction.currentPrice;
        if (auction.status === 'LIVE' && !leading && minimum <= budgets[chosen] && chance(left < 60 ? 0.5 : 0.15)) {
            think(0.2, 1.5);                            // typing the amount, while others bid
            const amount = minimum + auction.minIncrement * Math.floor(Math.random() * 3);
            const bid = call('bid', 'POST', `/api/v1/auctions/${chosen}/bids`,
                { body: { amount }, headers: key(), expected: [201, 400], parse: true });
            if (bid.status === 201) {
                myLastBid[chosen] = amount;
                count.bids_accepted.add(1);
            } else if (bid.json && bid.json.code === 'INVALID_MONEY_BID') {
                count.bids_outbid.add(1);
            } else if (bid.json && bid.json.code === 'BID_NO_LONGER_AVAILABLE') {
                count.bids_too_late.add(1);
            }
        }
        if (left < 60) think(0.5, 2); else think(2, 5);
    }
    payWonAuctions();
    think(2, 8);
}

// ---- the organizer, once, before the crowd arrives ---------------------------------------------------------------

export function setup() {
    token = null;
    const login = call('login', 'POST', '/api/v1/auth/login', {
        auth: false, parse: true, body: { email: 'organizer@loadtest.invalid', password: PASSWORD },
    });
    if (login.status !== 200) exec.test.abort('the organizer cannot log in');
    token = login.json.accessToken;

    const zonesOf = (event) => call('event-detail', 'GET', `/api/v1/events/${event}`, { parse: true }).json.zones;
    const vip = zonesOf(HOT).find((zone) => zone.name === 'VIP');
    const seats = call('seat-map', 'GET', `/api/v1/events/${HOT}/zones/${vip.publicId}/seats`, { parse: true }).json;

    // when each auction ends, as a share of the run: they close one after another
    const endings = [0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9];
    endings.forEach((share, i) => {
        const standing = i % 3 === 2;                   // some sell a standing ticket of a quieter event
        const event = standing ? eventId(2 + i) : HOT;
        const zone = standing ? zonesOf(event).find((z) => z.name === 'Standing') : vip;
        const startsAt = Date.now() + 15000;
        const made = call('auctions', 'POST', '/api/v1/auctions', {
            expected: [201], parse: true,
            body: {
                eventPublicId: event, zonePublicId: zone.publicId,
                seatPublicId: standing ? null : seats[i].publicId,
                startPrice: 1000000, minIncrement: 50000, reservePrice: 1500000,
                startsAt: new Date(startsAt).toISOString(),
                endsAt: new Date(startsAt + Math.max(45, SECONDS * share) * 1000).toISOString(),
                antiSnipeWindowSec: 30, extensionSec: 30,
            },
        });
        if (made.status !== 201) exec.test.abort(`auction ${i} was not created`);
    });
}

export function handleSummary(data) {
    return {
        [__ENV.SUMMARY]: JSON.stringify(data),
        stdout: `requests ${data.metrics.http_reqs.values.count}, unexpected ${data.metrics.unexpected ? data.metrics.unexpected.values.count : 0}\n`,
    };
}
