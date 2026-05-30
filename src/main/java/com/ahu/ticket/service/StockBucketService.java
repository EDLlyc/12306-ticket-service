package com.ahu.ticket.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class StockBucketService {

    private static final String RESERVE_LUA =
            "if (redis.call('exists', KEYS[2]) == 1) then return -6; end; " +
            "if (redis.call('exists', KEYS[1]) == 0) then return -2; end; " +
            "local total = tonumber(redis.call('get', KEYS[1])); " +
            "if (total <= 0) then return -1; end; " +
            "local start = tonumber(ARGV[1]); " +
            "local bucketCount = tonumber(ARGV[2]); " +
            "local ttlSeconds = tonumber(ARGV[3]); " +
            "local resultBase = tonumber(ARGV[4]); " +
            "for offset = 0, bucketCount - 1 do " +
            "  local bucketIdx = (start + offset) % bucketCount; " +
            "  local bucketKey = KEYS[bucketIdx + 3]; " +
            "  if (redis.call('exists', bucketKey) == 0) then return -4; end; " +
            "  local bucket = tonumber(redis.call('get', bucketKey)); " +
            "  if (bucket > 0) then " +
            "    redis.call('decr', KEYS[1]); " +
            "    redis.call('decr', bucketKey); " +
            "    redis.call('set', KEYS[2], bucketIdx, 'EX', ttlSeconds); " +
            "    local remaining = tonumber(redis.call('get', KEYS[1])); " +
            "    return remaining * resultBase + bucketIdx; " +
            "  end; " +
            "end; " +
            "return -3;";

    private static final String RELEASE_LUA =
            "if (redis.call('exists', KEYS[3]) == 0) then return -5; end; " +
            "if (redis.call('exists', KEYS[1]) == 0) then return -2; end; " +
            "if (redis.call('exists', KEYS[2]) == 0) then return -4; end; " +
            "redis.call('incr', KEYS[1]); " +
            "redis.call('incr', KEYS[2]); " +
            "redis.call('del', KEYS[3]); " +
            "return tonumber(redis.call('get', KEYS[1]));";
    private static final String RELEASE_WITHOUT_MAPPING_LUA =
            "if (redis.call('exists', KEYS[1]) == 0) then return -2; end; " +
            "local start = tonumber(ARGV[1]); " +
            "local bucketCount = tonumber(ARGV[2]); " +
            "local resultBase = tonumber(ARGV[3]); " +
            "for offset = 0, bucketCount - 1 do " +
            "  local bucketIdx = (start + offset) % bucketCount; " +
            "  local bucketKey = KEYS[bucketIdx + 2]; " +
            "  if (redis.call('exists', bucketKey) == 1) then " +
            "    redis.call('incr', KEYS[1]); " +
            "    redis.call('incr', bucketKey); " +
            "    local remaining = tonumber(redis.call('get', KEYS[1])); " +
            "    return remaining * resultBase + bucketIdx; " +
            "  end; " +
            "end; " +
            "return -4;";
    private static final long ORDER_BUCKET_TTL_DAYS = 30L;
    private static final long ORDER_BUCKET_TTL_SECONDS = TimeUnit.DAYS.toSeconds(ORDER_BUCKET_TTL_DAYS);
    private static final long RESERVE_RESULT_BUCKET_BASE = 1_000_000L;

    @Value("${app.stock.bucket-count:16}")
    private int bucketCount;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final DefaultRedisScript<Long> reserveScript = new DefaultRedisScript<>(RESERVE_LUA, Long.class);
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);
    private final DefaultRedisScript<Long> releaseWithoutMappingScript =
            new DefaultRedisScript<>(RELEASE_WITHOUT_MAPPING_LUA, Long.class);

    public void initSegmentedStock(String trainNumber, int totalStock) {
        redisTemplate.opsForValue().set(totalKey(trainNumber), String.valueOf(totalStock));

        int base = totalStock / bucketCount;
        int remainder = totalStock % bucketCount;
        for (int i = 0; i < bucketCount; i++) {
            int stock = i < remainder ? base + 1 : base;
            redisTemplate.opsForValue().set(bucketKey(trainNumber, i), String.valueOf(stock));
        }
    }

    public ReserveResult reserve(String trainNumber, String orderSn) {
        int start = modHash(orderSn, bucketCount);
        List<String> keys = buildReserveKeys(trainNumber, orderSn);
        Long result = redisTemplate.execute(
                reserveScript,
                keys,
                String.valueOf(start),
                String.valueOf(bucketCount),
                String.valueOf(ORDER_BUCKET_TTL_SECONDS),
                String.valueOf(RESERVE_RESULT_BUCKET_BASE)
        );
        if (result == null) {
            return ReserveResult.systemError("Redis 扣减执行返回空结果");
        }
        if (result >= 0) {
            int bucketIdx = decodeBucketIndex(result);
            int remainingTotal = decodeRemainingTotal(result);
            return ReserveResult.success(bucketIdx, remainingTotal);
        }
        if (result == -1L) {
            return ReserveResult.soldOut();
        }
        if (result == -2L || result == -4L) {
            return ReserveResult.notInitialized();
        }
        if (result == -6L) {
            return ReserveResult.duplicateOrder();
        }
        return ReserveResult.busy("分桶库存短暂不均衡，请稍后重试");
    }

    public boolean releaseByOrderSn(String trainNumber, String orderSn) {
        Integer bucketIdx = findBucketByOrderSn(orderSn);
        if (bucketIdx == null) {
            return false;
        }
        List<String> keys = Arrays.asList(totalKey(trainNumber), bucketKey(trainNumber, bucketIdx), orderBucketKey(orderSn));
        Long result = redisTemplate.execute(releaseScript, keys);
        return result != null && result >= 0;
    }

    public boolean hasOrderBucketMapping(String orderSn) {
        Boolean exists = redisTemplate.hasKey(orderBucketKey(orderSn));
        return Boolean.TRUE.equals(exists);
    }

    public boolean releaseWithoutOrderBucket(String trainNumber, String orderSn) {
        int start = modHash(orderSn, bucketCount);
        List<String> keys = new java.util.ArrayList<>(bucketCount + 1);
        keys.add(totalKey(trainNumber));
        for (int i = 0; i < bucketCount; i++) {
            keys.add(bucketKey(trainNumber, i));
        }
        Long result = redisTemplate.execute(
                releaseWithoutMappingScript,
                keys,
                String.valueOf(start),
                String.valueOf(bucketCount),
                String.valueOf(RESERVE_RESULT_BUCKET_BASE)
        );
        return result != null && result >= 0;
    }

    public void clearOrderBucketMapping(String orderSn) {
        redisTemplate.delete(orderBucketKey(orderSn));
    }

    public Integer getTotalStock(String trainNumber) {
        String raw = redisTemplate.opsForValue().get(totalKey(trainNumber));
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public int sumBucketStock(String trainNumber) {
        int sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            String raw = redisTemplate.opsForValue().get(bucketKey(trainNumber, i));
            if (raw == null) {
                continue;
            }
            try {
                sum += Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                // Ignore one broken bucket value and keep auditing.
            }
        }
        return sum;
    }

    public int getBucketCount() {
        return bucketCount;
    }

    private Integer findBucketByOrderSn(String orderSn) {
        String raw = redisTemplate.opsForValue().get(orderBucketKey(orderSn));
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int modHash(String text, int mod) {
        return Math.floorMod(text == null ? 0 : text.hashCode(), mod);
    }

    private List<String> buildReserveKeys(String trainNumber, String orderSn) {
        List<String> keys = new java.util.ArrayList<>(bucketCount + 2);
        keys.add(totalKey(trainNumber));
        keys.add(orderBucketKey(orderSn));
        for (int i = 0; i < bucketCount; i++) {
            keys.add(bucketKey(trainNumber, i));
        }
        return keys;
    }

    private int decodeBucketIndex(long encodedResult) {
        return (int) (encodedResult % RESERVE_RESULT_BUCKET_BASE);
    }

    private int decodeRemainingTotal(long encodedResult) {
        return (int) (encodedResult / RESERVE_RESULT_BUCKET_BASE);
    }

    private String totalKey(String trainNumber) {
        return "train:stock:" + trainNumber;
    }

    private String bucketKey(String trainNumber, int bucketIdx) {
        return "train:stock:" + trainNumber + ":b:" + bucketIdx;
    }

    private String orderBucketKey(String orderSn) {
        return "train:stock:orderBucket:" + orderSn;
    }

    @Getter
    public static class ReserveResult {
        private final boolean success;
        private final String code;
        private final String message;
        private final Integer bucketIndex;
        private final Integer remainingTotal;

        private ReserveResult(boolean success, String code, String message, Integer bucketIndex, Integer remainingTotal) {
            this.success = success;
            this.code = code;
            this.message = message;
            this.bucketIndex = bucketIndex;
            this.remainingTotal = remainingTotal;
        }

        public static ReserveResult success(int bucketIndex, int remainingTotal) {
            return new ReserveResult(true, "OK", "扣减成功", bucketIndex, remainingTotal);
        }

        public static ReserveResult soldOut() {
            return new ReserveResult(false, "SOLD_OUT", "库存不足", null, null);
        }

        public static ReserveResult notInitialized() {
            return new ReserveResult(false, "NOT_INITIALIZED", "库存未预热", null, null);
        }

        public static ReserveResult busy(String msg) {
            return new ReserveResult(false, "BUSY", msg, null, null);
        }

        public static ReserveResult systemError(String msg) {
            return new ReserveResult(false, "ERROR", msg, null, null);
        }

        public static ReserveResult duplicateOrder() {
            return new ReserveResult(false, "DUPLICATE_ORDER", "订单已存在库存预占记录", null, null);
        }
    }
}
