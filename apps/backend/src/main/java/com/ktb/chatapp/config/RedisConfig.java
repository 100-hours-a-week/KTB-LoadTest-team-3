package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient redissonClient(DataRedisConnectionDetails connectionDetails) {
        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();

        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://%s:%d".formatted(standalone.getHost(), standalone.getPort()))
                .setDatabase(standalone.getDatabase());

        String password = connectionDetails.getPassword();
        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }

        return Redisson.create(config);
    }
}
