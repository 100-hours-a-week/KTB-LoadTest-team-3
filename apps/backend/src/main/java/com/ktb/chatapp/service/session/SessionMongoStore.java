package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {
    
    private final SessionRepository sessionRepository;
    private final MongoTemplate mongoTemplate;
    
    @Override
    public Optional<Session> findByUserId(String userId) {
        return sessionRepository.findByUserId(userId);
    }

    @Override
    public ValidateAndTouchResult validateAndTouch(
            String userId,
            String sessionId,
            long activeSinceEpochMillis,
            long lastActivityEpochMillis,
            Instant expiresAt) {
        Query validSession = Query.query(new Criteria().andOperator(
                Criteria.where("userId").is(userId),
                Criteria.where("sessionId").is(sessionId),
                Criteria.where("lastActivity").gte(activeSinceEpochMillis)));
        Update touch = new Update()
                .max("lastActivity", lastActivityEpochMillis)
                .max("expiresAt", expiresAt);

        Session updated = mongoTemplate.findAndModify(
                validSession,
                touch,
                FindAndModifyOptions.options().returnNew(true),
                Session.class);
        if (updated != null) {
            return new ValidateAndTouchResult(ValidationStatus.VALID, updated);
        }

        Session current = sessionRepository.findByUserId(userId).orElse(null);
        if (current == null) {
            return new ValidateAndTouchResult(ValidationStatus.NOT_FOUND, null);
        }
        if (!sessionId.equals(current.getSessionId())) {
            return new ValidateAndTouchResult(ValidationStatus.SESSION_ID_MISMATCH, current);
        }
        if (current.getLastActivity() < activeSinceEpochMillis) {
            return new ValidateAndTouchResult(ValidationStatus.EXPIRED, current);
        }

        // The document may have become active between the failed update and diagnostic read.
        updated = mongoTemplate.findAndModify(
                validSession,
                touch,
                FindAndModifyOptions.options().returnNew(true),
                Session.class);
        return updated != null
                ? new ValidateAndTouchResult(ValidationStatus.VALID, updated)
                : new ValidateAndTouchResult(ValidationStatus.NOT_FOUND, null);
    }
    
    @Override
    public Session save(Session session) {
        return sessionRepository.save(session);
    }
    
    @Override
    public void delete(String userId, String sessionId) {
        Session session = sessionRepository.findByUserId(userId).orElse(null);
        if (session != null && sessionId.equals(session.getSessionId())) {
            sessionRepository.delete(session);
        }
    }
    
    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }
}
