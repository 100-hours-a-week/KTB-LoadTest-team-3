package com.ktb.chatapp.service.ratelimit;

import com.ktb.chatapp.model.RateLimit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of RateLimitStore.
 * Uses RateLimitRepository for persistence.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMongoStore implements RateLimitStore {

    private final MongoTemplate mongoTemplate;

    @Override
    public ConsumeResult consume(String clientId, int maxRequests, Instant now, Instant expiresAt) {
        String requestId = UUID.randomUUID().toString();
        RateLimit updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("clientId").is(clientId)),
                consumeUpdate(clientId, maxRequests, now, expiresAt, requestId),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                RateLimit.class);
        return new ConsumeResult(requestId.equals(updated.getLastRequestId()), updated);
    }

    private AggregationUpdate consumeUpdate(
            String clientId, int maxRequests, Instant now, Instant expiresAt, String requestId) {
        Document expiredOrMissing = new Document("$or", List.of(
                new Document("$lte", List.of("$expiresAt", now)),
                new Document("$eq", List.of(new Document("$type", "$expiresAt"), "missing"))));
        Document hasCapacity = new Document("$lt", List.of(
                new Document("$ifNull", List.of("$count", 0)), maxRequests));
        Document canConsume = new Document("$or", List.of(expiredOrMissing, hasCapacity));
        Document count = new Document("$cond", List.of(
                expiredOrMissing,
                1,
                new Document("$cond", List.of(
                        hasCapacity,
                        new Document("$add", List.of(new Document("$ifNull", List.of("$count", 0)), 1)),
                        "$count"))));
        Document expiration = new Document("$cond", List.of(
                expiredOrMissing,
                expiresAt,
                new Document("$ifNull", List.of("$expiresAt", expiresAt))));
        Document acceptedRequestId = new Document("$cond", List.of(
                canConsume, requestId, "$lastRequestId"));
        AggregationOperation setFields = context -> new Document("$set", new Document()
                .append("clientId", clientId)
                .append("count", count)
                .append("expiresAt", expiration)
                .append("lastRequestId", acceptedRequestId));
        return AggregationUpdate.from(List.of(setFields));
    }
}
