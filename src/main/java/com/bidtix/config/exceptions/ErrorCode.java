package com.bidtix.config.exceptions;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Every error the API can return on purpose.
 * <p>
 * The enum NAME is the stable {@code code} sent to clients (e.g. "SEAT_UNAVAILABLE"):
 * clients branch on it, so never rename a value once it is released.
 * The HTTP status and default message live next to it so there is one place to look.
 * <p>
 * Add new business errors here as features get built.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // --- generic ---
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request is invalid"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required or the token is invalid"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to do this"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state"),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, "The data was changed by someone else, please retry"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later"),
    RATE_LIMITER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable, please try again later"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "This Idempotency-Key was already used for a different request"),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "A request with this Idempotency-Key is still being processed"),

    // --- user / auth ---
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email is already registered"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token is missing, expired or revoked"),
    USER_NOTFOUND(HttpStatus.NOT_FOUND, "User not found"),
    // --- booking (example, extend later) ---
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Seat is no longer available"),
    EVENT_NOTFOUND(HttpStatus.NOT_FOUND, "Event not found"),
    ZONE_NOTFOUND(HttpStatus.NOT_FOUND, "Zone not found"),
    ORDER_NOTFOUND(HttpStatus.NOT_FOUND, "Order not found"),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "Order can no longer be paid"),
    ZONE_SOLD_OUT(HttpStatus.CONFLICT, "Not enough tickets left in this zone"),

    // --- event ---
    VENUE_NOTFOUND(HttpStatus.NOT_FOUND, "Venue not found"),
    VENUE_IN_USE(HttpStatus.CONFLICT, "The venue is used by an event"),
    ZONE_NAME_TAKEN(HttpStatus.CONFLICT, "A zone with this name already exists for the event"),
    EVENT_NOT_EDITABLE(HttpStatus.CONFLICT, "Only a DRAFT event can be changed"),
    EVENT_HAS_NO_ZONES(HttpStatus.CONFLICT, "An event needs at least one zone before it can be published"),
    EVENT_NOT_ON_SALE(HttpStatus.CONFLICT, "The event is not on sale at this time"),

    // --- auction ---
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Auction not found"),
    AUCTION_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Only an auction nobody has bid on can be cancelled"),
    EVENT_NOT_PUBLISHED(HttpStatus.CONFLICT, "Only a PUBLISHED event can have auctions"),
    INVALID_MONEY_BID(HttpStatus.BAD_REQUEST, "Bid amount is too low"),
    BID_NO_LONGER_AVAILABLE(HttpStatus.BAD_REQUEST, "The bid is no longer available");

    private final HttpStatus status;
    private final String message;
}
