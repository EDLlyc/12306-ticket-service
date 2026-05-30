package com.ahu.ticket.mq;

import com.ahu.ticket.order.OrderInventoryStateService;
import com.ahu.ticket.order.OrderTimeoutMessageService;
import com.ahu.ticket.service.StockBucketService;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "consumers-enabled", havingValue = "true")
@RocketMQMessageListener(topic = "ticket-topic", consumerGroup = "db-sync-group")
public class TicketConsumer implements RocketMQListener<String> {

    @Autowired
    private JdbcTemplate jdbcTemplate; // 用于执行原生 SQL 或简单插入

    @Autowired
    private StockBucketService stockBucketService;

    @Autowired
    private OrderTimeoutMessageService orderTimeoutMessageService;

    @Autowired
    private OrderInventoryStateService orderInventoryStateService;

    @Autowired
    private TrainInventoryLedgerService trainInventoryLedgerService;

    @Override
    @Transactional // 开启事务，保证库存和订单同时成功
    public void onMessage(String message) {
        // 1. 解析消息：车次,用户名,订单号
        String[] parts = message.split(",");
        String trainNumber = parts[0];
        String orderSn = parts[2];

        try {
            log.info(">>> [MQ 消费者] 正在处理订单，准备扣减数据库库存并更新状态：{}", orderSn);

            // 2. 幂等性控制：先抢占订单处理权，避免重复消费导致重复扣库存
            int claimedRows = jdbcTemplate.update(
                    "UPDATE t_order SET status = 'PROCESSING' WHERE order_sn = ? AND status = 'CREATED'",
                    orderSn);

            if (claimedRows == 0) {
                log.warn("!!! [MQ 消费者] 订单 {} 状态已被处理或非 PENDING，自动拦截重复消费。", orderSn);
                return;
            }

            // 3. 异步扣减 MySQL 真实库存
            boolean success = trainInventoryLedgerService.lockAvailableInventory(trainNumber);

            if (success) {
                jdbcTemplate.update(
                        "UPDATE t_order SET status = 'PENDING' WHERE order_sn = ? AND status = 'PROCESSING'",
                        orderSn);
                orderInventoryStateService.markInventoryConfirmed(orderSn);
                try {
                    orderTimeoutMessageService.sendOrderTimeoutMessage(orderSn);
                } catch (Exception e) {
                    log.warn("<<< [MQ 消费者] 订单 {} 延迟关单消息发送失败，将依赖定时兜底。", orderSn, e);
                }
                log.info("<<< [MQ 消费者] 订单处理成功：{}，订单进入待支付状态并锁定 MySQL 可售库存。", orderSn);
            } else {
                jdbcTemplate.update(
                        "UPDATE t_order SET status = 'FAILED' WHERE order_sn = ? AND status = 'PROCESSING'",
                        orderSn);
                try {
                    boolean released = stockBucketService.releaseByOrderSn(trainNumber, orderSn);
                    if (released) {
                        orderInventoryStateService.markInventoryReleased(orderSn);
                    }
                } catch (Exception ex) {
                    log.error("<<< [MQ 消费者] 订单 {} 回补 Redis 库存失败，需要人工介入。", orderSn, ex);
                }
                stockBucketService.clearOrderBucketMapping(orderSn);
                log.error("<<< [MQ 消费者] 订单 {} 锁定 MySQL 可售库存失败，订单已标记 FAILED 并触发 Redis 补偿。", orderSn);
            }
        } catch (Exception e) {
            log.error("!!! [MQ 消费者] 处理订单 {} 时发生异常", orderSn, e);
            throw e; // 抛出异常让 RocketMQ 重新投递
        }
    }
}
