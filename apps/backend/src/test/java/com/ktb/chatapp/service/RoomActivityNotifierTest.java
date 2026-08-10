package com.ktb.chatapp.service;

import com.ktb.chatapp.event.RoomActivityEvent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomActivityNotifierTest {

    @Mock private RecentMessageCounter recentMessageCounter;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RoomActivityNotifier notifier;

    private RoomActivityNotifier notifier() {
        notifier = new RoomActivityNotifier(
                recentMessageCounter,
                eventPublisher,
                Duration.ofMillis(30),
                Executors.newScheduledThreadPool(2));
        return notifier;
    }

    @AfterEach
    void tearDown() {
        if (notifier != null) {
            notifier.shutdown();
        }
    }

    @Test
    void notifyMessageStored_singleMessage_eventuallyPublishesRecentMessageCount() {
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(7);

        notifier().notifyMessageStored("room-1");

        ArgumentCaptor<RoomActivityEvent> eventCaptor = ArgumentCaptor.forClass(RoomActivityEvent.class);
        verify(eventPublisher, timeout(1000)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getRoomId()).isEqualTo("room-1");
        assertThat(eventCaptor.getValue().getRecentMessageCount()).isEqualTo(7);
    }

    @Test
    void notifyMessageStored_sameRoomBurst_coalescesIntoOneCount() {
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(10);
        RoomActivityNotifier roomActivityNotifier = notifier();

        for (int i = 0; i < 10; i++) {
            roomActivityNotifier.notifyMessageStored("room-1");
        }

        verify(eventPublisher, timeout(1000)).publishEvent(any(RoomActivityEvent.class));
        verify(recentMessageCounter, times(1)).countRecentMessages("room-1");
        verify(eventPublisher, after(100).times(1)).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_differentRooms_aggregatesIndependently() {
        when(recentMessageCounter.countRecentMessages("room-1")).thenReturn(3);
        when(recentMessageCounter.countRecentMessages("room-2")).thenReturn(5);
        RoomActivityNotifier roomActivityNotifier = notifier();

        roomActivityNotifier.notifyMessageStored("room-1");
        roomActivityNotifier.notifyMessageStored("room-2");

        verify(eventPublisher, timeout(1000).times(2)).publishEvent(any(RoomActivityEvent.class));
        verify(recentMessageCounter).countRecentMessages("room-1");
        verify(recentMessageCounter).countRecentMessages("room-2");
    }

    @Test
    void notifyMessageStored_nullRoomId_doesNothing() {
        notifier().notifyMessageStored(null);

        verifyNoInteractions(recentMessageCounter);
        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_counterFailure_doesNotBlockAndLaterRequestRetries() {
        when(recentMessageCounter.countRecentMessages("room-1"))
                .thenThrow(new RuntimeException("mongo down"))
                .thenReturn(8);
        RoomActivityNotifier roomActivityNotifier = notifier();

        roomActivityNotifier.notifyMessageStored("room-1");
        verify(recentMessageCounter, timeout(1000)).countRecentMessages("room-1");
        verify(eventPublisher, never()).publishEvent(any(RoomActivityEvent.class));

        roomActivityNotifier.notifyMessageStored("room-1");

        verify(recentMessageCounter, timeout(1000).times(2)).countRecentMessages("room-1");
        verify(eventPublisher, timeout(1000)).publishEvent(any(RoomActivityEvent.class));
    }

    @Test
    void notifyMessageStored_staleInFlightResult_doesNotOverwriteNewerResult() throws Exception {
        CountDownLatch firstCountStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCount = new CountDownLatch(1);
        when(recentMessageCounter.countRecentMessages("room-1"))
                .thenAnswer(ignored -> {
                    firstCountStarted.countDown();
                    assertThat(releaseFirstCount.await(1, TimeUnit.SECONDS)).isTrue();
                    return 1;
                })
                .thenReturn(2);
        RoomActivityNotifier roomActivityNotifier = notifier();

        roomActivityNotifier.notifyMessageStored("room-1");
        assertThat(firstCountStarted.await(1, TimeUnit.SECONDS)).isTrue();
        roomActivityNotifier.notifyMessageStored("room-1");
        releaseFirstCount.countDown();

        ArgumentCaptor<RoomActivityEvent> eventCaptor = ArgumentCaptor.forClass(RoomActivityEvent.class);
        verify(eventPublisher, timeout(1000)).publishEvent(eventCaptor.capture());
        verify(recentMessageCounter, times(2)).countRecentMessages("room-1");
        verify(eventPublisher, after(100).times(1)).publishEvent(any(RoomActivityEvent.class));
        assertThat(eventCaptor.getValue().getRecentMessageCount()).isEqualTo(2);
    }

    @Test
    void springContext_usesInjectionConstructor() {
        new ApplicationContextRunner()
                .withBean(RecentMessageCounter.class, () -> recentMessageCounter)
                .withUserConfiguration(RoomActivityNotifier.class)
                .withPropertyValues("room-activity.recent-count-coalesce-delay=25ms")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).hasSingleBean(RoomActivityNotifier.class);
                });
    }
}
