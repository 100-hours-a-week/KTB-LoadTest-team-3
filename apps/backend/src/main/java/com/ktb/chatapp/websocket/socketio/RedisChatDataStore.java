package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

public class RedisChatDataStore implements ChatDataStore {

    private static final String MAP_NAME = "socketio:chat-data";

    private final RMap<String, Object> storage;

    public RedisChatDataStore(RedissonClient redissonClient) {
        this.storage = redissonClient.getMap(MAP_NAME);
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = storage.get(key);
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(type.cast(value));
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        storage.put(key, value);
    }

    @Override
    public void delete(String key) {
        storage.remove(key);
    }

    @Override
    public int size() {
        return storage.size();
    }
}
