package com.ahu.ticket.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ahu.ticket.entity.Train;
import com.ahu.ticket.service.ITrainService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/train") // 统一前缀
public class TicketController {

    @Autowired
    private StringRedisTemplate redisTemplate; // 统一使用 String 类型模板，彻底告别强转报错

    @Autowired
    private ITrainService trainService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    // Lua 脚本：原子性扣减库存
    private static final String LUA_STOCK_DECREASE = "if (redis.call('exists', KEYS[1]) == 1) then " +
            "    local stock = tonumber(redis.call('get', KEYS[1])); " +
            "    if (stock > 0) then " +
            "        redis.call('decr', KEYS[1]); " +
            "        return stock - 1; " +
            "    end; " +
            "    return -1; " +
            "end; " +
            "return -2;";

    /**
     * 1. 库存预热：将数据库库存同步到 Redis
     */
    @GetMapping("/init")
    public String initStock(@RequestParam String trainNumber) {
        Train train = trainService.getOne(new QueryWrapper<Train>().eq("train_number", trainNumber));
        if (train != null) {
            redisTemplate.opsForValue().set("train:stock:" + trainNumber, String.valueOf(train.getStock()));
            
            // --- 【核心增强】初始化布隆过滤器，防御海量非法请求穿透 ---
            org.redisson.api.RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("bloom:train_numbers");
            bloomFilter.tryInit(100000L, 0.03); // 预计百万级车次，误差率3%
            bloomFilter.add(trainNumber); // 写入合法车次号
            
            log.info("【预热成功】车次: {}，库存: {} 及布隆过滤器已同步至 Redis", trainNumber, train.getStock());
            return "【预热成功】车次：" + trainNumber + "，库存(及布隆过滤器)已同步至 Redis。";
        }
        return "预热失败：车次不存在。";
    }

    // 在类内部定义一个本地缓存对象
    // 设置最大存储 1000 个车次，数据 1 分钟后自动失效
    private com.github.benmanes.caffeine.cache.Cache<String, String> localCache = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @GetMapping("/query")
    @SentinelResource(value = "queryTrain", blockHandler = "handleQueryBlock")
    public String queryTrain(@RequestParam String trainNumber) {
        String cacheKey = "train:info:" + trainNumber;

        // --- 【第零级防御】查布隆过滤器 (防止海量非法请求打爆缓存空值) ---
        org.redisson.api.RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("bloom:train_numbers");
        if (bloomFilter.isExists() && !bloomFilter.contains(trainNumber)) {
            log.warn("布隆过滤器拦截非法车次: {}", trainNumber);
            return "非法请求：该车次根本不存在！(布隆过滤器物理拦截)";
        }

        // --- 【第一级防御】查本地缓存 (Caffeine) ---
        String trainInfo = localCache.getIfPresent(cacheKey);
        if (trainInfo != null) {
            log.debug("Caffeine 本地缓存命中: {}", trainNumber);
            return "【来自本地内存】" + trainInfo;
        }

        // --- 【第二级防御】查 Redis (RedisTemplate) ---
        trainInfo = redisTemplate.opsForValue().get(cacheKey);
        if (trainInfo != null) {
            // 同步回填给本地缓存，下次就快了
            localCache.put(cacheKey, trainInfo);
            log.debug("Redis 缓存命中并回填 Caffeine: {}", trainNumber);
            return "【来自远程 Redis】" + trainInfo;
        }

        // --- 【第三级防御】查数据库 (MySQL + 分布式锁) ---
        RLock lock = redissonClient.getLock("lock:query:" + trainNumber);
        try {
            if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                // 双重检查
                trainInfo = redisTemplate.opsForValue().get(cacheKey);
                if (trainInfo != null)
                    return trainInfo;

                Train train = trainService.getOne(new QueryWrapper<Train>().eq("train_number", trainNumber));
                if (train != null) {
                    String result = train.toString();
                    // 同时写入二级缓存 (Redis) 和 一级缓存 (Caffeine)
                    redisTemplate.opsForValue().set(cacheKey, result, 30, TimeUnit.MINUTES);
                    localCache.put(cacheKey, result);
                    log.info("缓存穿透到 DB 并回填: {}", trainNumber);
                    return "【来自 MySQL】" + result;
                } else {
                    // 防穿透逻辑保持不变
                    redisTemplate.opsForValue().set(cacheKey, "EMPTY", 5, TimeUnit.MINUTES);
                    localCache.put(cacheKey, "EMPTY");
                    return "该车次不存在";
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
        return "系统繁忙";
    }

    /**
     * 3. 异步事务下单
     * 解决：原子性 + 重复消费问题
     */
    @PostMapping("/book/lua")
    public String bookTicketLua(@RequestParam String trainNumber, @RequestParam String token) {
        // 校验登录
        String username = redisTemplate.opsForValue().get("login:token:" + token);
        if (username == null)
            return "请先登录！";

        // 生成唯一订单号（解决幂等性）
        String orderSn = UUID.randomUUID().toString();
        String messageBody = trainNumber + "," + username + "," + orderSn;

        // 构建消息
        Message<String> msg = MessageBuilder.withPayload(messageBody).build();

        // 【优化重点】采用事务消息：先发 Half 消息，由 Listener 执行 Redis 扣减
        // 这样可以保证 Redis 扣减成功了，MQ 消息一定能发出去
        rocketMQTemplate.sendMessageInTransaction("ticket-topic", msg, trainNumber);

        log.info("事务消息已发送，orderSn={}, trainNumber={}", orderSn, trainNumber);
        return "【请求已受理】订单号：" + orderSn + "，正在排队占座中...";
    }

    /**
     * 查询所有车次（保留原逻辑，统一路径）
     */
    @GetMapping("/all")
    public List<Train> getAll() {
        return trainService.list();
    }

    /**
     * Sentinel 限流兜底方法
     */
    public String handleQueryBlock(String trainNumber, BlockException ex) {
        log.warn("【Sentinel】查询接口触发限流/熔断！参数: {}", trainNumber);
        return "【系统繁忙】当前查票人数过多，请稍后再试！(Sentinel 保护中)";
    }
}