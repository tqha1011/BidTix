package com.bidtix.config.exceptions;

import lombok.RequiredArgsConstructor;
import com.bidtix.config.metrics.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * The single place that turns exceptions into HTTP error responses.
 * <p>
 * Every response is a {@link ProblemDetail} (RFC 9457, content type application/problem+json):
 * <pre>
 * {
 *   "type": "about:blank",
 *   "title": "Conflict",
 *   "status": 409,
 *   "detail": "Seat is no longer available",
 *   "code": "SEAT_UNAVAILABLE"
 * }
 * </pre>
 * Clients should branch on {@code code}, never on the text of {@code detail}.
 * <p>
 * Extending {@link ResponseEntityExceptionHandler} gives us the same format for free for
 * framework errors (malformed JSON, unsupported method, unknown URL, ...).
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final Metrics metrics;

    /** Errors our own code threw on purpose. */
    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        ProblemDetail body = problem(ex.getErrorCode(), ex.getMessage());
        ex.getProperties().forEach(body::setProperty);
        return body;
    }

    /** {@code @Valid} on a request body failed: 400 plus the list of bad fields. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();

        ProblemDetail body = problem(ErrorCode.INVALID_REQUEST);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Login failed or token is bad: 401.
     * Wrong password, unknown email and locked account all return the SAME message, so an
     * attacker cannot use the response to find out which emails exist or are locked.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        boolean loginFailure = ex instanceof BadCredentialsException || ex instanceof AccountStatusException;
        return problem(loginFailure ? ErrorCode.INVALID_CREDENTIALS : ErrorCode.UNAUTHORIZED);
    }

    /** Logged in, but the role is not allowed (e.g. a CUSTOMER calling an ADMIN endpoint): 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(ErrorCode.FORBIDDEN);
    }

    /**
     * Two requests changed the same row at the same time and our {@code @Version} check caught it.
     * The client can safely retry.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(ErrorCode.CONCURRENT_UPDATE);
    }

    /**
     * A DB constraint rejected the write (unique email, seat already ticketed, ...).
     * Services should catch the cases they know and throw a specific BusinessException;
     * this is the safety net for the rest. The raw message names tables and constraints, so
     * it goes to the log only, never to the client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Database constraint violated: {}", ex.getMostSpecificCause().getMessage());
        return problem(ErrorCode.CONFLICT);
    }

    /** Anything unexpected is a bug: log everything, tell the client nothing. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(ErrorCode.INTERNAL_ERROR);
    }

    private ProblemDetail problem(ErrorCode errorCode) {
        return problem(errorCode, errorCode.getMessage());
    }

    /** Every error response built from an {@link ErrorCode} passes here, so this is where they are counted. */
    private ProblemDetail problem(ErrorCode errorCode, String detail) {
        metrics.apiError(errorCode);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), detail);
        problem.setProperty("code", errorCode.name());
        return problem;
    }
}
