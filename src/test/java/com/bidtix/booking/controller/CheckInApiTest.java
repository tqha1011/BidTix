package com.bidtix.booking.controller;

import com.bidtix.support.ApiTest;

/**
 * Checking tickets in at the gate. Skeleton: no test yet.
 * <p>
 * To cover:
 * <ul>
 *   <li>a VALID ticket is admitted once: 200, USED, used_at set (standing and seated)</li>
 *   <li>a second scan: 409 TICKET_ALREADY_USED with usedAt</li>
 *   <li>a CANCELLED ticket: 409; an unknown one, or one of another event: 404</li>
 *   <li>too early, after the end, or an event that is not PUBLISHED: 409 CHECK_IN_NOT_OPEN</li>
 *   <li>another organizer: 403; a customer: 403 (also add the route to MethodAuthorizationTest)</li>
 *   <li>many scans of one ticket at the same instant: exactly one 200. Remove the status condition of the UPDATE and
 *       see this test fail before trusting it.</li>
 * </ul>
 * Catalog (test support) creates events and zones. ApiTest turns rate limiting off.
 */
class CheckInApiTest extends ApiTest {
}
