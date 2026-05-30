package com.ahu.ticket;

import com.ahu.ticket.service.StockBucketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class StockBucketServiceTest {

    private static final long RESERVE_RESULT_BUCKET_BASE = 1_000_000L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StockBucketService stockBucketService;

    @BeforeEach
    public void setup() {
        stockBucketService = new StockBucketService();
        ReflectionTestUtils.setField(stockBucketService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(stockBucketService, "bucketCount", 16);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    public void testReleaseByOrderSn_returnsFalseWhenBucketMappingMissing() {
        when(valueOperations.get("train:stock:orderBucket:o1")).thenReturn(null);

        boolean released = stockBucketService.releaseByOrderSn("G1001", "o1");

        assertFalse(released);
        verify(redisTemplate, never()).execute(any(), anyList());
    }

    @Test
    public void testReleaseByOrderSn_executesLuaWhenBucketMappingExists() {
        when(valueOperations.get("train:stock:orderBucket:o1")).thenReturn("3");
        when(redisTemplate.execute(any(), anyList())).thenReturn(12L);

        boolean released = stockBucketService.releaseByOrderSn("G1001", "o1");

        assertTrue(released);
        verify(redisTemplate).execute(any(), anyList());
    }

    @Test
    public void testReserve_executesAtomicLuaWithoutExtraRedisSet() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenReturn(15L * RESERVE_RESULT_BUCKET_BASE + 3L);

        StockBucketService.ReserveResult result = stockBucketService.reserve("G1001", "o1");

        assertTrue(result.isSuccess());
        assertEquals(3, result.getBucketIndex());
        assertEquals(15, result.getRemainingTotal());
        verify(redisTemplate).execute(any(), anyList(), any(), any(), any(), any());
        verify(valueOperations, never()).set(any(), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void testReserve_returnsDuplicateOrderWhenOrderAlreadyReserved() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any())).thenReturn(-6L);

        StockBucketService.ReserveResult result = stockBucketService.reserve("G1001", "o1");

        assertFalse(result.isSuccess());
        assertEquals("DUPLICATE_ORDER", result.getCode());
    }
}
