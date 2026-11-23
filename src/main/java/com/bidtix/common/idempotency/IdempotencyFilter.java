package com.bidtix.common.idempotency;

import com.bidtix.common.idempotency.service.IdempotencyService;
import com.bidtix.common.idempotency.service.IdempotencyService.Claim;
import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Makes a retried POST safe: the same {@code Idempotency-Key} with the same body runs once and then replays the
 * stored response. Only the routes in {@code bidtix.idempotency.paths} are touched.
 * <p>
 * It runs AFTER the Spring Security filter chain (the order is below the chain's), so the caller is already known and
 * a request that security refuses never reaches the {@code idempotency_keys} table. Keys belong to a user.
 * <p>
 * Flow: claim the key (an {@code INSERT ... ON CONFLICT DO NOTHING}, committed before the request runs), run the
 * request, then store the response if it was a success (2xx). Any other outcome releases the key: a failed request did
 * nothing, so running it again is safe and gives the client a fresh answer. A duplicate that arrives while the first
 * is still running gets 409; same key with another body gets 422.
 * <p>
 * Limit: the claim and the response are separate transactions from the business change. If the server dies after the
 * business commit but before the response is stored, the key stays PROCESSING and, after
 * {@code processing-timeout}, the same request runs once more. The business rules (for example a bid amount that can
 * only be accepted once) are the last line of defence there.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 64; // idempotency_keys.idem_key
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final IdempotencyProperties properties;
    private final IdempotencyService service;
    private final HandlerExceptionResolver resolver;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyProperties properties, IdempotencyService service,
                             @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.properties = properties;
        this.service = service;
        this.resolver = resolver;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !properties.enabled()
                || !HttpMethod.POST.matches(request.getMethod())
                || properties.paths().stream().noneMatch(pattern -> matcher.match(pattern, request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            chain.doFilter(request, response); // security decides; nobody to key the request to
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            refuse(request, response, new BusinessException(ErrorCode.INVALID_REQUEST,
                    HEADER + " header is required (1 to " + MAX_KEY_LENGTH + " characters)"));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            refuse(request, response, new BusinessException(ErrorCode.INVALID_REQUEST, "Request body is too large"));
            return;
        }

        Long userId;
        Claim claim;
        try {
            userId = service.userId(UUID.fromString(principal.getName()));
            claim = service.claim(userId, key, request.getMethod(), request.getRequestURI(), sha256(body));
        } catch (BusinessException e) {
            refuse(request, response, e);
            return;
        }

        switch (claim.outcome()) {
            case KEY_REUSED -> refuse(request, response, new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
            case IN_PROGRESS -> refuse(request, response, new BusinessException(ErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS));
            case REPLAY -> replay(response, claim);
            case PROCEED -> run(userId, key, new CachedBodyRequestWrapper(request, body), response, chain);
        }
    }

    private void run(Long userId, String key, CachedBodyRequestWrapper request, HttpServletResponse response,
                     FilterChain chain) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            service.release(userId, key);
            throw e;
        }
        // stored BEFORE the client gets the response: a client that never sees it can retry and still get a replay
        int status = wrapped.getStatus();
        try {
            if (status >= 200 && status < 300) {
                service.complete(userId, key, status, new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
            } else {
                service.release(userId, key);
            }
        } catch (RuntimeException e) {
            log.warn("Could not store the response of idempotency key; releasing it", e);
            service.release(userId, key);
        }
        wrapped.copyBodyToResponse();
    }

    private static void replay(HttpServletResponse response, Claim claim) throws IOException {
        response.setStatus(claim.responseStatus());
        response.setHeader(REPLAYED_HEADER, "true");
        if (claim.responseBody() != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(claim.responseBody().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Hands the error to Spring MVC's resolver, so GlobalExceptionHandler writes the usual JSON error. */
    private void refuse(HttpServletRequest request, HttpServletResponse response, BusinessException error) {
        resolver.resolveException(request, response, null, error);
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
    }
}
