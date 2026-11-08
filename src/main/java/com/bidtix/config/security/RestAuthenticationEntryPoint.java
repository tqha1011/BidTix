package com.bidtix.config.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Called by Spring Security when a request has no token, or a bad / expired one: 401.
 * <p>
 * This happens in the security FILTER, before any controller runs, so
 * {@code @RestControllerAdvice} would normally never see it. Instead of writing the JSON by
 * hand, we hand the exception to Spring MVC's resolver, which runs
 * {@link com.bidtix.config.exceptions.GlobalExceptionHandler}. One format, one place.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver resolver;

    public RestAuthenticationEntryPoint(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                         @NonNull AuthenticationException exception) {
        // Bearer-token APIs are expected to say which scheme they want (RFC 6750).
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        resolver.resolveException(request, response, null, exception);
    }
}
