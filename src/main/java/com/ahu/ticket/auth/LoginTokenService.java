package com.ahu.ticket.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LoginTokenService {

    public static final String LOGIN_TOKEN_PREFIX = "login:token:";
    public static final String CURRENT_USERNAME_ATTR = "currentUsername";
    public static final String TOKEN_HEADER = "X-Login-Token";

    private final StringRedisTemplate redisTemplate;

    public LoginTokenService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String issueToken(String username) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(LOGIN_TOKEN_PREFIX + token, username, 30, TimeUnit.MINUTES);
        return token;
    }

    public String resolveUsername(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(LOGIN_TOKEN_PREFIX + token);
    }
}
