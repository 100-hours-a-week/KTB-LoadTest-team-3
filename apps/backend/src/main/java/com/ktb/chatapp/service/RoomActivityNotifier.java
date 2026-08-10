package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 새 메시지가 저장되면 채팅방 목록의 활성도 지표를 갱신하도록 알린다.
 */
@Slf4j
@Component
public class RoomActivityNotifier {

    private final RecentMessageCounter recentMessageCounter;
    private final ApplicationEventPublisher eventPublisher;
    private final long coalesceDelayMillis;
    private final ScheduledExecutorService executor;
    private final ConcurrentMap<String, PendingAggregation> pendingAggregations = new ConcurrentHashMap<>();

    @Autowired
    public RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher,
            @Value("${room-activity.recent-count-coalesce-delay:150ms}") String coalesceDelay) {
        this(recentMessageCounter, eventPublisher, DurationStyle.detectAndParse(coalesceDelay),
                Executors.newScheduledThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "recent-message-count");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    RoomActivityNotifier(
            RecentMessageCounter recentMessageCounter,
            ApplicationEventPublisher eventPublisher,
            Duration coalesceDelay,
            ScheduledExecutorService executor) {
        this.recentMessageCounter = recentMessageCounter;
        this.eventPublisher = eventPublisher;
        this.coalesceDelayMillis = Math.max(0L, coalesceDelay.toMillis());
        this.executor = executor;
    }

    public void notifyMessageStored(String roomId) {
        if (roomId == null) {
            return;
        }

        try {
            pendingAggregations.compute(roomId, (ignored, pending) -> {
                long generation = pending == null ? 1L : pending.generation.incrementAndGet();
                if (pending != null && pending.future != null) {
                    pending.future.cancel(false);
                }

                PendingAggregation next = new PendingAggregation(generation);
                next.future = executor.schedule(
                        () -> aggregateAndPublish(roomId, generation),
                        coalesceDelayMillis,
                        TimeUnit.MILLISECONDS);
                return next;
            });
        } catch (Exception e) {
            pendingAggregations.remove(roomId);
            log.error("roomActivity 비동기 집계 예약 실패: roomId={}", roomId, e);
        }
    }

    private void aggregateAndPublish(String roomId, long generation) {
        try {
            int recentMessageCount = recentMessageCounter.countRecentMessages(roomId);
            pendingAggregations.computeIfPresent(roomId, (ignored, pending) -> {
                if (pending.generation.get() != generation) {
                    return pending;
                }
                eventPublisher.publishEvent(new RoomActivityEvent(this, roomId, recentMessageCount));
                return null;
            });
        } catch (Exception e) {
            pendingAggregations.computeIfPresent(roomId, (ignored, pending) ->
                    pending.generation.get() == generation ? null : pending);
            log.error("roomActivity 비동기 집계/이벤트 발행 실패: roomId={}", roomId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class PendingAggregation {
        private final AtomicLong generation;
        private volatile ScheduledFuture<?> future;

        private PendingAggregation(long generation) {
            this.generation = new AtomicLong(generation);
        }
    }
}
