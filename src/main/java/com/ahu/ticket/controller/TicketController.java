package com.ahu.ticket.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ahu.ticket.auth.LoginTokenService;
import com.ahu.ticket.common.Result;
import com.ahu.ticket.entity.Train;
import com.ahu.ticket.service.ITrainService;
import com.ahu.ticket.service.StockBucketService;
import com.ahu.ticket.service.TrainBloomFilterService;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import com.ahu.ticket.service.TrainQueryService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/train") // 统一前缀
public class TicketController {

    @Autowired
    private ITrainService trainService;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private StockBucketService stockBucketService;

    @Autowired
    private TrainQueryService trainQueryService;

    @Autowired
    private TrainInventoryLedgerService trainInventoryLedgerService;

    @Autowired
    private TrainBloomFilterService trainBloomFilterService;

    /**
     * 1. 库存预热：将数据库库存同步到 Redis
     */
    @GetMapping("/init")
    public Result<String> initStock(@RequestParam String trainNumber) {
        Train train = trainService.getOne(new QueryWrapper<Train>().eq("train_number", trainNumber));
        if (train != null) {
            int initialAvailableStock = trainInventoryLedgerService.getInitialAvailableStock(train);
            stockBucketService.initSegmentedStock(trainNumber, initialAvailableStock);
            trainQueryService.preheatStaticTrainCache(train);
            trainBloomFilterService.addTrainNumber(trainNumber);

            log.info("【预热成功】车次: {}，可售库存: {}、静态信息及布隆过滤器已同步至缓存/Redis", trainNumber, initialAvailableStock);
            return Result.success("【预热成功】车次：" + trainNumber + "，库存、静态信息(及布隆过滤器)已同步至缓存/Redis。");
        }
        return Result.notFound("预热失败：车次不存在。");
    }

    @PostMapping("/query/cache/preheat/all")
    public Result<Map<String, Object>> preheatAllStaticTrainCaches() {
        int preheatedCount = trainQueryService.preheatAllStaticTrainCaches();
        return Result.success(Map.of(
                "preheatedCount", preheatedCount,
                "localCacheEstimatedSize", trainQueryService.queryMetrics().get("localCacheEstimatedSize")
        ));
    }

    @GetMapping("/query")
    @SentinelResource(value = "queryTrain", blockHandler = "handleQueryBlock")
    public Result<String> queryTrain(@RequestParam String trainNumber) {
        return Result.success(trainQueryService.queryTrain(trainNumber));
    }

    @GetMapping("/query/metrics")
    public Result<Map<String, Object>> queryMetrics() {
        return Result.success(trainQueryService.queryMetrics());
    }

    @PostMapping("/query/experiment/reset")
    public Result<Map<String, Object>> resetQueryExperiment(@RequestParam(required = false) String trainNumber) {
        return Result.success(trainQueryService.resetQueryExperiment(trainNumber));
    }

    @PostMapping("/query/cache/reset")
    public Result<Map<String, Object>> resetQueryCache(@RequestParam(required = false) String trainNumber) {
        return Result.success(trainQueryService.resetQueryCache(trainNumber));
    }

    /**
     * 3. 异步事务下单
     * 解决：原子性 + 重复消费问题
     */
    @PostMapping("/book/lua")
    @SentinelResource(value = "bookTicket", blockHandler = "handleBookBlock")
    public Result<String> bookTicketLua(@RequestParam String trainNumber,
                                        @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        // 生成唯一订单号（解决幂等性）
        String orderSn = UUID.randomUUID().toString();
        String messageBody = trainNumber + "," + username + "," + orderSn;

        // 构建消息
        Message<String> msg = MessageBuilder.withPayload(messageBody).build();

        // 【优化重点】采用事务消息：先发 Half 消息，由 Listener 执行 Redis 扣减
        // 这样可以保证 Redis 扣减成功了，MQ 消息一定能发出去
        rocketMQTemplate.sendMessageInTransaction("ticket-topic", msg, trainNumber);

        log.info("事务消息已发送，orderSn={}, trainNumber={}", orderSn, trainNumber);
        return Result.success("【请求已受理】订单号：" + orderSn + "，正在锁座中。锁座成功后将进入待支付状态，请在 15 分钟内完成支付。");
    }

    /**
     * 查询所有车次（保留原逻辑，统一路径）
     */
    @GetMapping("/all")
    public List<Train> getAll() {
        return trainQueryService.getAllTrainsWithLiveStock();
    }

    /**
     * Sentinel 限流兜底方法
     */
    public Result<String> handleQueryBlock(String trainNumber, BlockException ex) {
        log.warn("【Sentinel】查询接口触发限流/熔断！参数: {}", trainNumber);
        return Result.tooManyRequests("【系统繁忙】当前查票人数过多，请稍后再试！(Sentinel 保护中)");
    }

    public Result<String> handleBookBlock(String trainNumber, String username, BlockException ex) {
        log.warn("【Sentinel】抢票接口触发限流/熔断！trainNumber={}, username={}", trainNumber, username);
        return Result.tooManyRequests("【系统繁忙】当前抢票人数过多，请稍后再试！(Sentinel 保护中)");
    }
}
