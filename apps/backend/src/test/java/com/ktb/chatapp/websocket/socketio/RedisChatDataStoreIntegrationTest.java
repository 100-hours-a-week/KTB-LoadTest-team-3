package com.ktb.chatapp.websocket.socketio;

import com.ktb.chatapp.config.MongoTestContainer;
import com.ktb.chatapp.config.RedisTestContainer;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import({MongoTestContainer.class, RedisTestContainer.class})
@TestPropertySource(properties = "socketio.enabled=true")
class RedisChatDataStoreIntegrationTest {

    @Autowired
    private ChatDataStore chatDataStore;

    @Test
    void wiredAsRedisBackedStore() {
        assertThat(chatDataStore).isInstanceOf(RedisChatDataStore.class);
    }

    @Test
    void roundTripsSocketUserThroughRedis() {
        String key = "conn_users:userid:redis-test-user";
        SocketUser user = new SocketUser("redis-test-user", "tester", "auth-session-1", "socket-1");

        chatDataStore.set(key, user);

        assertThat(chatDataStore.get(key, SocketUser.class)).contains(user);

        chatDataStore.delete(key);
        assertThat(chatDataStore.get(key, SocketUser.class)).isEmpty();
    }

    @Test
    void roundTripsRoomSetThroughRedis() {
        String key = "userroom:roomids:redis-test-user";
        // UserRooms always stores a mutable HashSet, so the test mirrors that exactly.
        Set<String> rooms = new HashSet<>(Set.of("room-1", "room-2"));

        chatDataStore.set(key, rooms);

        @SuppressWarnings("unchecked")
        var stored = chatDataStore.get(key, Set.class).map(s -> (Set<String>) s);
        assertThat(stored).isPresent();
        assertThat(stored.get()).containsExactlyInAnyOrder("room-1", "room-2");
    }

    @Test
    void sizeReflectsStoredEntries() {
        String key = "chat-data-store-test:size-check";
        int before = chatDataStore.size();

        chatDataStore.set(key, "value");
        assertThat(chatDataStore.size()).isEqualTo(before + 1);

        chatDataStore.delete(key);
        assertThat(chatDataStore.size()).isEqualTo(before);
    }
}
