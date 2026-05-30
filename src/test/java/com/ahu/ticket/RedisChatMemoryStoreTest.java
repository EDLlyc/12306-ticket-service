package com.ahu.ticket;

import com.ahu.ticket.rag.RedisChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisChatMemoryStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private JdbcTemplate jdbcTemplate;
    private RedisChatMemoryStore memoryStore;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        memoryStore = new RedisChatMemoryStore(redisTemplate, jdbcTemplate);
    }

    @Test
    @DisplayName("默认读取只加载短期窗口，不查询 MySQL 长期摘要")
    void defaultGetMessagesDoesNotLoadLongTermSummary() {
        when(valueOperations.get("chat:memory:u::s")).thenReturn(null);

        List<ChatMessage> messages = memoryStore.getMessages("u::s");

        assertTrue(messages.isEmpty());
        verify(valueOperations).get("chat:memory:u::s");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("显式开启后才读取并注入长期摘要")
    void explicitGetMessagesLoadsLongTermSummary() {
        when(valueOperations.get("chat:memory:u::s")).thenReturn(null);
        when(valueOperations.get("chat:summary:u::s")).thenReturn(null);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("u"), eq("s")))
                .thenReturn(List.of("用户之前咨询过上海到杭州的学生票政策。"));

        List<ChatMessage> messages = memoryStore.getMessagesWithLongTermSummary("u::s");

        assertEquals(1, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertTrue(((SystemMessage) messages.get(0)).text().contains("学生票政策"));
        verify(valueOperations).set(
                eq("chat:summary:u::s"),
                eq("用户之前咨询过上海到杭州的学生票政策。"),
                eq(180L),
                eq(TimeUnit.MINUTES));
    }
}
