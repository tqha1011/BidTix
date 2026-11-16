package com.bidtix.config.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits a controller method (or every method of a controller) with the named policy from
 * {@code bidtix.rate-limit.policies}. Checked by {@link RateLimitInterceptor}, after Spring Security, so a logged-in
 * caller is already known.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** Name of a policy in application.yaml, e.g. {@code "auth-login"}. */
    String policy();

    /** What is counted: calls from one IP address, or calls from one logged-in user. */
    KeyBy by() default KeyBy.IP;

    enum KeyBy {
        /** Needed for endpoints that need no login (login, register). Behind a proxy see the note on the interceptor. */
        IP,
        /** The token's subject; falls back to the IP when nobody is logged in. */
        USER
    }
}
