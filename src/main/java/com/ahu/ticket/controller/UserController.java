package com.ahu.ticket.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostMapping("/login")
    public String login(@RequestParam String username) {
        // 1. 生成令牌
        String token = UUID.randomUUID().toString();

        // 2. 存入 Redis，有效期 30 分钟
        redisTemplate.opsForValue().set("login:token:" + token, username, 30, TimeUnit.MINUTES);

        return token;
    }

    /**
     * 查询登录状态：通过 token 获取用户名
     */
    @GetMapping("/check")
    public Map<String, Object> checkLogin(@RequestParam String token) {
        String username = redisTemplate.opsForValue().get("login:token:" + token);
        if (username != null) {
            return Map.of("loggedIn", true, "username", username);
        }
        return Map.of("loggedIn", false);
    }

    /**
     * 查询指定用户的所有订单（供前端订单面板使用）
     */
    @GetMapping("/orders")
    public List<Map<String, Object>> queryOrders(@RequestParam String username) {
        return jdbcTemplate.queryForList(
                "SELECT order_sn, train_number, username, status FROM t_order WHERE username = ? ORDER BY order_sn DESC",
                username);
    }
}