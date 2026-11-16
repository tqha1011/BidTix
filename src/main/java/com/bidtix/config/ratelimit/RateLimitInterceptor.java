package com.bidtix.config.ratelimit;

import com.bidtix.config.exceptions.BusinessException;
import com.bidtix.config.exceptions.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the rate limits. It runs inside Spring MVC, after the Spring Security filters, so:
 * <ul>
 *   <li>the caller of a protected endpoint is already known (the token's subject), and</li>
 *   <li>a {@link BusinessException} thrown here goes through {@code GlobalExceptionHandler}: the client gets the
 *       usual error body with {@code code = TOO_MANY_REQUESTS}.</li>
 * </ul>
 * Which policy applies: the {@link RateLimited} annotation on the method or its controller; without one, a logged-in
 * caller gets the {@code default-user} policy and an anonymous caller is not limited.
 *
 * <p><b>Client IP.</b> It is {@code request.getRemoteAddr()}. Behind a reverse proxy or load balancer that is the
 * proxy's address, so everybody would share one counter; set {@code server.forward-headers-strategy} so Spring
 * resolves the real address, and make sure the proxy overwrites {@code X-Forwarded-For} (a client can send its own).
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        if (!rateLimitService.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }

        String user = loggedInUser();
        RateLimited annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimited.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimited.class);
        }

        String policy;
        String subject;
        if (annotation != null) {
            policy = annotation.policy();
            subject = annotation.by() == RateLimited.KeyBy.USER && user != null
                    ? "user:" + user : "ip:" + clientIp(request);
        } else if (user != null) {
            policy = RateLimitProperties.DEFAULT_USER_POLICY;
            subject = "user:" + user;
        } else {
            return true;
        }

        RateLimitService.Decision decision = rateLimitService.check(policy, subject);
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        return true;
    }

    /** The token's subject, or null when nobody is logged in. */
    private static String loggedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    private static String clientIp(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
