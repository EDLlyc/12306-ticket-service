package com.ahu.ticket.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ahu.ticket.entity.Train;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Service
public class TrainQueryService {

    private static final String TRAIN_STATIC_CACHE_KEY_PREFIX = "train:info:static:";
    private static final String EMPTY_CACHE_VALUE = "EMPTY";
    private static final long LOCAL_STATIC_CACHE_TTL_MINUTES = 10L;
    private static final long REDIS_STATIC_CACHE_TTL_HOURS = 12L;
    private static final long REDIS_EMPTY_CACHE_TTL_MINUTES = 5L;
    private static final long SINGLE_FLIGHT_WAIT_SECONDS = 3L;

    private final StringRedisTemplate redisTemplate;
    private final ITrainService trainService;
    private final org.redisson.api.RedissonClient redissonClient;
    private final StockBucketService stockBucketService;
    private final TrainInventoryLedgerService trainInventoryLedgerService;
    private final TrainBloomFilterService trainBloomFilterService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CompletableFuture<StaticTrainLoadResult>> inFlightLoads = new ConcurrentHashMap<>();

    private final com.github.benmanes.caffeine.cache.Cache<String, String> localCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(LOCAL_STATIC_CACHE_TTL_MINUTES, TimeUnit.MINUTES)
                    .build();

    private final LongAdder queryRequestCount = new LongAdder();
    private final LongAdder caffeineHitCount = new LongAdder();
    private final LongAdder bloomRejectCount = new LongAdder();
    private final LongAdder redisHitCount = new LongAdder();
    private final LongAdder dbHitCount = new LongAdder();
    private final LongAdder emptyResultCount = new LongAdder();
    private final LongAdder lockBusyCount = new LongAdder();

    public TrainQueryService(StringRedisTemplate redisTemplate,
                             ITrainService trainService,
                             org.redisson.api.RedissonClient redissonClient,
                             StockBucketService stockBucketService,
                             TrainInventoryLedgerService trainInventoryLedgerService,
                             TrainBloomFilterService trainBloomFilterService,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.trainService = trainService;
        this.redissonClient = redissonClient;
        this.stockBucketService = stockBucketService;
        this.trainInventoryLedgerService = trainInventoryLedgerService;
        this.trainBloomFilterService = trainBloomFilterService;
        this.objectMapper = objectMapper;
    }

    public String queryTrain(String trainNumber) {
        queryRequestCount.increment();
        String cacheKey = TRAIN_STATIC_CACHE_KEY_PREFIX + trainNumber;

        String cachedStaticTrain = localCache.getIfPresent(cacheKey);
        if (cachedStaticTrain != null) {
            caffeineHitCount.increment();
            log.debug("Caffeine 本地缓存命中: {}", trainNumber);
            return renderPayloadResult(trainNumber, cachedStaticTrain, "【静态信息来自本地内存】");
        }

        if (!trainBloomFilterService.mightContainTrainNumber(trainNumber)) {
            bloomRejectCount.increment();
            log.warn("布隆过滤器拦截非法车次: {}", trainNumber);
            return "非法请求：该车次根本不存在！(布隆过滤器物理拦截)";
        }

        cachedStaticTrain = redisTemplate.opsForValue().get(cacheKey);
        if (cachedStaticTrain != null) {
            redisHitCount.increment();
            localCache.put(cacheKey, cachedStaticTrain);
            log.debug("Redis 缓存命中并回填 Caffeine: {}", trainNumber);
            return renderPayloadResult(trainNumber, cachedStaticTrain, "【静态信息来自远程 Redis】");
        }

        return queryTrainWithSingleFlight(trainNumber, cacheKey);
    }

    public List<Train> getAllTrainsWithLiveStock() {
        List<Train> trains = trainService.list();
        for (Train train : trains) {
            Integer liveStock = resolveCurrentStock(train.getTrainNumber(), trainInventoryLedgerService.resolveAvailableStock(train));
            train.setStock(liveStock);
            train.setAvailableStock(liveStock);
        }
        return trains;
    }

    public Map<String, Object> queryMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("requests", queryRequestCount.sum());
        metrics.put("caffeineHits", caffeineHitCount.sum());
        metrics.put("redisHits", redisHitCount.sum());
        metrics.put("dbHits", dbHitCount.sum());
        metrics.put("bloomRejects", bloomRejectCount.sum());
        metrics.put("emptyResults", emptyResultCount.sum());
        metrics.put("lockBusy", lockBusyCount.sum());
        metrics.put("localCacheEstimatedSize", localCache.estimatedSize());
        return metrics;
    }

    public Map<String, Object> resetQueryExperiment(String trainNumber) {
        invalidateStaticTrainCache(trainNumber);
        resetCounters();
        return queryMetrics();
    }

    public Map<String, Object> resetQueryCache(String trainNumber) {
        invalidateStaticTrainCache(trainNumber);
        return queryMetrics();
    }

    public void preheatStaticTrainCache(Train train) {
        if (train == null || train.getTrainNumber() == null || train.getTrainNumber().isBlank()) {
            return;
        }
        String cacheKey = TRAIN_STATIC_CACHE_KEY_PREFIX + train.getTrainNumber();
        String payload = serializeStaticTrain(TrainStaticSnapshot.from(train));
        writeStaticPayloadToCaches(cacheKey, payload);
    }

    public int preheatAllStaticTrainCaches() {
        List<Train> trains = trainService.list();
        for (Train train : trains) {
            preheatStaticTrainCache(train);
        }
        return trains.size();
    }

    public void invalidateStaticTrainCache(String trainNumber) {
        if (trainNumber == null || trainNumber.isBlank()) {
            localCache.invalidateAll();
            inFlightLoads.clear();
            Set<String> cacheKeys = redisTemplate.keys(TRAIN_STATIC_CACHE_KEY_PREFIX + "*");
            if (cacheKeys != null && !cacheKeys.isEmpty()) {
                redisTemplate.delete(cacheKeys);
            }
            return;
        }
        String cacheKey = TRAIN_STATIC_CACHE_KEY_PREFIX + trainNumber;
        localCache.invalidate(cacheKey);
        inFlightLoads.remove(cacheKey);
        redisTemplate.delete(cacheKey);
    }

    private String queryTrainWithSingleFlight(String trainNumber, String cacheKey) {
        CompletableFuture<StaticTrainLoadResult> leaderFuture = new CompletableFuture<>();
        CompletableFuture<StaticTrainLoadResult> existingFuture = inFlightLoads.putIfAbsent(cacheKey, leaderFuture);
        if (existingFuture != null) {
            return awaitInFlightResult(trainNumber, cacheKey, existingFuture);
        }

        try {
            StaticTrainLoadResult result = loadStaticTrainWithDistributedLock(trainNumber, cacheKey);
            leaderFuture.complete(result);
            return renderLoadResult(trainNumber, result);
        } catch (RuntimeException e) {
            leaderFuture.completeExceptionally(e);
            throw e;
        } finally {
            inFlightLoads.remove(cacheKey, leaderFuture);
        }
    }

    private String awaitInFlightResult(String trainNumber,
                                       String cacheKey,
                                       CompletableFuture<StaticTrainLoadResult> future) {
        try {
            StaticTrainLoadResult result = future.get(SINGLE_FLIGHT_WAIT_SECONDS, TimeUnit.SECONDS);
            return renderLoadResult(trainNumber, result);
        } catch (TimeoutException e) {
            String cachedStaticTrain = localCache.getIfPresent(cacheKey);
            if (cachedStaticTrain != null) {
                caffeineHitCount.increment();
                return renderPayloadResult(trainNumber, cachedStaticTrain, "【静态信息来自本地内存】");
            }

            cachedStaticTrain = redisTemplate.opsForValue().get(cacheKey);
            if (cachedStaticTrain != null) {
                redisHitCount.increment();
                localCache.put(cacheKey, cachedStaticTrain);
                return renderPayloadResult(trainNumber, cachedStaticTrain, "【静态信息来自远程 Redis】");
            }

            lockBusyCount.increment();
            return "系统繁忙";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "系统繁忙";
        } catch (ExecutionException e) {
            throw unwrapCompletionFailure(e);
        }
    }

    private StaticTrainLoadResult loadStaticTrainWithDistributedLock(String trainNumber, String cacheKey) {
        RLock lock = redissonClient.getLock("lock:query:" + trainNumber);
        try {
            if (lock.tryLock(3, TimeUnit.SECONDS)) {
                String cachedStaticTrain = redisTemplate.opsForValue().get(cacheKey);
                if (cachedStaticTrain != null) {
                    redisHitCount.increment();
                    localCache.put(cacheKey, cachedStaticTrain);
                    return new StaticTrainLoadResult(cachedStaticTrain, "【静态信息来自远程 Redis】");
                }

                dbHitCount.increment();
                Train train = trainService.getOne(new QueryWrapper<Train>().eq("train_number", trainNumber));
                if (train != null) {
                    String staticTrainPayload = serializeStaticTrain(TrainStaticSnapshot.from(train));
                    writeStaticPayloadToCaches(cacheKey, staticTrainPayload);
                    log.info("缓存穿透到 DB 并回填: {}", trainNumber);
                    return new StaticTrainLoadResult(staticTrainPayload, "【静态信息来自 MySQL】");
                }

                writeEmptyPayloadToCaches(cacheKey);
                return new StaticTrainLoadResult(EMPTY_CACHE_VALUE, "");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StaticTrainLoadResult.busy();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        String cachedStaticTrain = redisTemplate.opsForValue().get(cacheKey);
        if (cachedStaticTrain != null) {
            redisHitCount.increment();
            localCache.put(cacheKey, cachedStaticTrain);
            return new StaticTrainLoadResult(cachedStaticTrain, "【静态信息来自远程 Redis】");
        }

        lockBusyCount.increment();
        return StaticTrainLoadResult.busy();
    }

    private String renderLoadResult(String trainNumber, StaticTrainLoadResult result) {
        if (result.busy) {
            return "系统繁忙";
        }
        return renderPayloadResult(trainNumber, result.payload, result.sourcePrefix);
    }

    private String renderPayloadResult(String trainNumber, String payload, String sourcePrefix) {
        if (EMPTY_CACHE_VALUE.equals(payload)) {
            emptyResultCount.increment();
            return "该车次不存在";
        }
        TrainStaticSnapshot snapshot = deserializeStaticTrain(payload);
        Integer liveStock = resolveCurrentStock(trainNumber);
        return sourcePrefix + formatTrainDetail(snapshot, liveStock);
    }

    private void writeStaticPayloadToCaches(String cacheKey, String payload) {
        redisTemplate.opsForValue().set(cacheKey, payload, REDIS_STATIC_CACHE_TTL_HOURS, TimeUnit.HOURS);
        localCache.put(cacheKey, payload);
    }

    private void writeEmptyPayloadToCaches(String cacheKey) {
        redisTemplate.opsForValue().set(cacheKey, EMPTY_CACHE_VALUE, REDIS_EMPTY_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        localCache.put(cacheKey, EMPTY_CACHE_VALUE);
    }

    private void resetCounters() {
        queryRequestCount.reset();
        caffeineHitCount.reset();
        bloomRejectCount.reset();
        redisHitCount.reset();
        dbHitCount.reset();
        emptyResultCount.reset();
        lockBusyCount.reset();
    }

    private Integer resolveCurrentStock(String trainNumber) {
        return resolveCurrentStock(trainNumber, null);
    }

    private Integer resolveCurrentStock(String trainNumber, Integer dbFallbackStock) {
        Integer redisStock = stockBucketService.getTotalStock(trainNumber);
        if (redisStock != null) {
            return redisStock;
        }
        if (dbFallbackStock != null) {
            return dbFallbackStock;
        }
        Train train = trainService.getOne(new QueryWrapper<Train>().eq("train_number", trainNumber));
        return trainInventoryLedgerService.resolveAvailableStock(train);
    }

    private String serializeStaticTrain(TrainStaticSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize train static info failed", e);
        }
    }

    private TrainStaticSnapshot deserializeStaticTrain(String payload) {
        try {
            return objectMapper.readValue(payload, TrainStaticSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Deserialize train static info failed", e);
        }
    }

    private RuntimeException unwrapCompletionFailure(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("Singleflight load failed", cause);
    }

    private String formatTrainDetail(TrainStaticSnapshot snapshot, Integer liveStock) {
        return "Train(" +
                "id=" + snapshot.id +
                ", trainNumber=" + snapshot.trainNumber +
                ", startStation=" + snapshot.startStation +
                ", endStation=" + snapshot.endStation +
                ", startTime=" + snapshot.startTime +
                ", endTime=" + snapshot.endTime +
                ", availableStock=" + liveStock +
                ")";
    }

    private static final class StaticTrainLoadResult {
        private final String payload;
        private final String sourcePrefix;
        private final boolean busy;

        private StaticTrainLoadResult(String payload, String sourcePrefix) {
            this.payload = payload;
            this.sourcePrefix = sourcePrefix;
            this.busy = false;
        }

        private StaticTrainLoadResult(boolean busy) {
            this.payload = null;
            this.sourcePrefix = "";
            this.busy = busy;
        }

        private static StaticTrainLoadResult busy() {
            return new StaticTrainLoadResult(true);
        }
    }

    private static final class TrainStaticSnapshot {
        public Long id;
        public String trainNumber;
        public String startStation;
        public String endStation;
        public LocalDateTime startTime;
        public LocalDateTime endTime;

        private static TrainStaticSnapshot from(Train train) {
            TrainStaticSnapshot snapshot = new TrainStaticSnapshot();
            snapshot.id = train.getId();
            snapshot.trainNumber = train.getTrainNumber();
            snapshot.startStation = train.getStartStation();
            snapshot.endStation = train.getEndStation();
            snapshot.startTime = train.getStartTime();
            snapshot.endTime = train.getEndTime();
            return snapshot;
        }
    }
}
