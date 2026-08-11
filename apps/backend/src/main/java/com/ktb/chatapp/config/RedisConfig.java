package com.ktb.chatapp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient redissonClient(
            DataRedisConnectionDetails connectionDetails,
            @Value("${REDIS_CLUSTER_NODES:}") String clusterNodes) {
        DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();

        Config config = new Config();
        String password = connectionDetails.getPassword();

        if (StringUtils.hasText(clusterNodes)) {
            String[] nodeAddresses = java.util.Arrays.stream(clusterNodes.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .toArray(String[]::new);
            var serverConfig = config.useClusterServers()
                    .addNodeAddress(nodeAddresses);
            if (StringUtils.hasText(password)) {
                serverConfig.setPassword(password);
            }
        } else {
            var serverConfig = config.useSingleServer()
                    .setAddress("redis://%s:%d".formatted(standalone.getHost(), standalone.getPort()))
                    .setDatabase(standalone.getDatabase());
            if (StringUtils.hasText(password)) {
                serverConfig.setPassword(password);
            }
        }

        return Redisson.create(config);
    }
}
