package com.ahu.ticket.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 这里连接你 Docker 里的 Redis
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setDatabase(0); // 与之前的 0 号库保持一致
        return Redisson.create(config);
    }
}