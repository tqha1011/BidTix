package com.bidtix.config.exceptions;

import lombok.Getter;

import java.util.Map;

/**
 * Thrown by services when a business rule is broken. It knows nothing about HTTP:
 * {@link GlobalExceptionHandler} turns it into a response using the {@link ErrorCode}.
 *
 * <pre>
 * throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE);
 * throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE, "Seat A12 is already held");
 * </pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** Extra fields added to the response body next to {@code code}, e.g. the ids that were rejected. */
    private final Map<String, Object> properties;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public BusinessException(ErrorCode errorCode, String detail, Map<String, ?> properties) {
        // The last two "false" turn off the stack trace: business errors are expected
        // (a taken seat is not a bug), so building a stack trace on every one is wasted work.
        super(detail, null, false, false);
        this.errorCode = errorCode;
        this.properties = Map.copyOf(properties);
    }
}
