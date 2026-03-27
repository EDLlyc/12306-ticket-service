package com.ahu.ticket.mq;

import com.ahu.ticket.entity.Train;
import com.ahu.ticket.service.ITrainService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RocketMQMessageListener(topic = "ticket-topic", consumerGroup = "db-sync-group")
public class TicketConsumer implements RocketMQListener<String> {

    @Autowired
    private ITrainService trainService;

    @Autowired
    private JdbcTemplate jdbcTemplate; // 用于执行原生 SQL 或简单插入

    @Override
    @Transactional // 开启事务，保证库存和订单同时成功
    public void onMessage(String message) {
        // 1. 解析消息：车次,用户名,订单号
        String[] parts = message.split(",");
        String trainNumber = parts[0];
        // String username = parts[1]; // 由于是更新状态，不再需要 username
        String orderSn = parts[2];

        try {
            log.info(">>> [MQ 消费者] 正在处理订单，准备扣减数据库库存并更新状态：{}", orderSn);

            // 2. 幂等性控制：尝试将订单状态从 PENDING 更新为 SUCCESS
            // 如果返回 0，说明这个订单已经被处理过（或者不存在），直接忽略，防止重复消费
            int updatedRows = jdbcTemplate.update(
                    "UPDATE t_order SET status = 'SUCCESS' WHERE order_sn = ? AND status = 'PENDING'", 
                    orderSn);

            if (updatedRows == 0) {
                log.warn("!!! [MQ 消费者] 订单 {} 状态已被处理或非 PENDING，自动拦截重复消费。", orderSn);
                return;
            }

            // 3. 异步扣减 MySQL 真实库存
            boolean success = trainService.update(new UpdateWrapper<Train>()
                    .setSql("stock = stock - 1")
                    .eq("train_number", trainNumber)
                    .gt("stock", 0));

            if (success) {
                log.info("<<< [MQ 消费者] 订单处理成功：{}，MySQL 库存已扣减。", orderSn);
            } else {
                // 严重错误：Redis 扣了，但 MySQL 库存不足（按理不该发生）。需要告警或人工介入。
                log.error("<<< [MQ 消费者] 订单 {} 扣减 MySQL 库存失败，可能出现超卖！", orderSn);
            }
        } catch (Exception e) {
            log.error("!!! [MQ 消费者] 处理订单 {} 时发生异常", orderSn, e);
            throw e; // 抛出异常让 RocketMQ 重新投递
        }
    }
}