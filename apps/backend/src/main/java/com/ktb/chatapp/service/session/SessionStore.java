package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import java.time.Instant;
import java.util.Optional;

/**
 * Data store interface for session storage.
 * Provides operations for storing and retrieving session data.
 */
public interface SessionStore {
    
    /**
     * Find session by user ID
     *
     * @param userId the user identifier
     * @return Optional containing the Session if found, empty otherwise
     */
    Optional<Session> findByUserId(String userId);

    /**
     * Validates the session identity and timeout and atomically extends an active session.
     */
    ValidateAndTouchResult validateAndTouch(
            String userId,
            String sessionId,
            long activeSinceEpochMillis,
            long lastActivityEpochMillis,
            Instant expiresAt);
    
    /**
     * Save or update session
     *
     * @param session the session to save
     * @return the saved session
     */
    Session save(Session session);
    
    /**
     * Delete all sessions for a user
     *
     * @param userId the user identifier
     */
    void deleteAll(String userId);
    
    void delete(String userId, String sessionId);

    enum ValidationStatus {
        VALID,
        NOT_FOUND,
        SESSION_ID_MISMATCH,
        EXPIRED
    }

    record ValidateAndTouchResult(ValidationStatus status, Session session) {
    }
}
