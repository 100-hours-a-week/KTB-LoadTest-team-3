package com.ktb.chatapp.service;

import com.ktb.chatapp.repository.MessageRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.group;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * 채팅방 목록에 노출하는 "최근 메시지 수"의 집계 창을 한곳에서 관리한다.
 */
@Component
@RequiredArgsConstructor
public class RecentMessageCounter {

    static final Duration RECENT_WINDOW = Duration.ofMinutes(30);

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    public int countRecentMessages(String roomId) {
        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);
        return (int) messageRepository.countRecentMessagesByRoomId(roomId, since);
    }

    // N개 방 렌더링 시, 각 방마다 개별 count 쿼리 N+1 문제 해결에 사용
    public Map<String, Integer> countRecentMessagesForRooms(Collection<String> roomIds) {
        Map<String, Integer> counts = new HashMap<>();
        if (roomIds == null || roomIds.isEmpty()) {
            return counts;
        }

        LocalDateTime since = LocalDateTime.now().minus(RECENT_WINDOW);

        Aggregation aggregation = Aggregation.newAggregation(
                match(where("room").in(roomIds).and("timestamp").gte(since)),
                group("room").count().as("count")
        );

        AggregationResults<RoomMessageCount> results =
                mongoTemplate.aggregate(aggregation, "messages", RoomMessageCount.class);

        for (RoomMessageCount result : results.getMappedResults()) {
            counts.put(result.id(), result.count());
        }
        return counts;
    }

    private record RoomMessageCount(String id, int count) {
    }
}
