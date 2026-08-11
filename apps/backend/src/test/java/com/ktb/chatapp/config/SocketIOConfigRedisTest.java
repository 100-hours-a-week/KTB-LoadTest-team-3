package com.ktb.chatapp.config;

import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "socketio.enabled=true")
@Import({MongoTestContainer.class, RedisTestContainer.class})
class SocketIOConfigRedisTest {

    @Autowired
    private SocketIOServer socketIOServer;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void socketIOServerUsesRedissonStoreFactoryWhenEnabled() {
        assertThat(socketIOServer.getConfiguration().getStoreFactory())
                .isInstanceOf(RedissonStoreFactory.class);
        assertThat(redissonClient.isShutdown()).isFalse();
    }
}
