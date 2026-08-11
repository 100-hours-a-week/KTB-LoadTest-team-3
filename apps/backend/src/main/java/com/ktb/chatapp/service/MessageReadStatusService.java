package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MongoTemplate mongoTemplate;

    /**
     * 메시지 읽음 상태 업데이트
     *
     * @param messageIds 읽음 상태를 업데이트할 메시지 리스트
     * @param userId 읽은 사용자 ID
     */
    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds.isEmpty()) {
            return;
        }

        var readerInfo = Message.MessageReader.builder()
                .userId(userId)
                .readAt(LocalDateTime.now())
                .build();

        // 메시지 개수만큼 findById+save를 반복하던 N+1 패턴 대신, 아직 이 사용자가
        // 읽지 않은 메시지만 골라 한 번의 multi-update 쿼리로 readers를 갱신
        try {
            Query query = new Query(Criteria.where("id").in(messageIds)
                    .and("readers.userId").ne(userId));
            Update update = new Update().push("readers", readerInfo);

            var result = mongoTemplate.updateMulti(query, update, Message.class);
            log.debug("Read status updated for {} messages ({} matched) by user {}",
                    result.getModifiedCount(), result.getMatchedCount(), userId);
        } catch (Exception e) {
            log.error("Read status update error for user {}", userId, e);
        }
    }
}
