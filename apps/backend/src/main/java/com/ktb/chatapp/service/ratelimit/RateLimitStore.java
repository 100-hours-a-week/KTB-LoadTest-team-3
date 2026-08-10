package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Instant;

/**
 * Data store interface for rate limit storage.
 * Provides operations for storing and retrieving rate limit data.
 */
public interface RateLimitStore {

    /**
     * Atomically consumes one request when the current window has capacity.
     *
     * @param clientId the client identifier
     * @param maxRequests maximum requests allowed in an active window
     * @param now time used to decide whether the stored window has expired
     * @param expiresAt expiration time for a newly created or reset window
     * @return the decision and the current rate-limit document
     */
    ConsumeResult consume(String clientId, int maxRequests, Instant now, Instant expiresAt);

    record ConsumeResult(boolean allowed, RateLimit rateLimit) {
    }
}
