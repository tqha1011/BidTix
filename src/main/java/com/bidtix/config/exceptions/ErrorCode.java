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

    // --- user / auth ---
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email is already registered"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token is missing, expired or revoked"),
    USER_NOTFOUND(HttpStatus.NOT_FOUND, "User not found"),
    // --- booking (example, extend later) ---
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Seat is no longer available"),
    EVENT_NOTFOUND(HttpStatus.NOT_FOUND, "Event not found"),
    ZONE_NOTFOUND(HttpStatus.NOT_FOUND, "Zone not found");

    private final HttpStatus status;
    private final String message;
}
