package com.ahu.ticket.mq;

import com.ahu.ticket.order.OrderInventoryStateService;
import com.ahu.ticket.service.StockBucketService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;

import java.time.LocalDateTime;

/**
 * RocketMQ 事务消息监听器
 *
 * 面试话术要点：
 * 1. Half 消息 → Broker 暂存，不投递给消费者
 * 2. executeLocalTransaction → 执行本地事务（Redis Lua 原子扣减 + 订单落库）
 *    - 成功 → COMMIT（Broker 投递消息给消费者异步扣 MySQL）
 *    - 失败 → ROLLBACK（消息作废，库存不动）
 * 3. checkLocalTransaction → 兜底回查（服务宕机/网络超时时 Broker 主动问）
 *    - 查本地订单表是否存在该 orderSn → 决定补发 COMMIT 或 ROLLBACK
 */
@Slf4j
@RocketMQTransactionListener
public class TicketTransactionListener implements RocketMQLocalTransactionListener {

    @Autowired
    private StockBucketService stockBucketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderInventoryStateService orderInventoryStateService;

    /**
     * 第一步：Half 半消息发送成功后，Broker 回调此方法执行本地事务
     *
     * 核心流程：Redis Lua 原子扣减 → 订单预记录落库 → 决定 COMMIT 或 ROLLBACK
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        String messageBody = new String((byte[]) msg.getPayload());
        String[] parts = messageBody.split(",");
        String trainNumber = parts[0];
        String username = parts[1];
        String orderSn = parts[2];

        log.info("【MQ 事务】接收到半消息，开始执行本地事务，订单: {}", orderSn);

        try {
            // ====== 第 1 步：Redis Lua 原子扣减库存 ======
            StockBucketService.ReserveResult reserveResult = stockBucketService.reserve(trainNumber, orderSn);
            if (!reserveResult.isSuccess()) {
                log.warn("【MQ 事务】Redis 扣减失败，事务回滚。trainNumber={}, orderSn={}, code={}",
                        trainNumber, orderSn, reserveResult.getCode());
                return RocketMQLocalTransactionState.ROLLBACK;
            }

            log.info("【MQ 事务】Redis 扣减成功，bucket={}, 剩余总库存: {}，正在落库预订单...",
                    reserveResult.getBucketIndex(), reserveResult.getRemainingTotal());

            // ====== 第 2 步：本地订单预记录落库(幂等：UNIQUE KEY idx_order_sn) ======
            LocalDateTime now = LocalDateTime.now();
            orderInventoryStateService.insertCreatedOrder(orderSn, trainNumber, username, now);

            log.info("【MQ 事务】本地预订单落库成功，COMMIT 确认消息。orderSn={}", orderSn);
            return RocketMQLocalTransactionState.COMMIT;

        } catch (DuplicateKeyException e) {
            // 幂等拦截：重复的 orderSn，说明已经处理过
            log.warn("【MQ 事务】检测到重复订单号，幂等拦截。orderSn={}", orderSn);
            return RocketMQLocalTransactionState.COMMIT; // 已处理过，直接确认

        } catch (Exception e) {
            log.error("【MQ 事务】本地事务执行异常，事务回滚。orderSn={}", orderSn, e);
            // 回滚：Redis 库存需要回补（补偿策略）
            try {
                stockBucketService.releaseByOrderSn(trainNumber, orderSn);
                log.info("【MQ 事务】Redis 库存已回补。trainNumber={}", trainNumber);
            } catch (Exception ex) {
                log.error("【MQ 事务】Redis 库存回补失败，需人工介入！trainNumber={}", trainNumber, ex);
            }
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 第二步（兜底回查）：Broker 定期回查未确认的事务状态
     *
     * 面试必考点：当 executeLocalTransaction 超时/宕机没返回明确状态时触发
     * 策略：查本地订单表判断该 orderSn 是否已落库
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String messageBody = new String((byte[]) msg.getPayload());
        String[] parts = messageBody.split(",");
        String orderSn = parts[2];

        log.info("【MQ 回查】Broker 触发事务回查，orderSn={}", orderSn);

        try {
            // 查本地数据库：该订单是否已经落库
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_order WHERE order_sn = ?",
                    Integer.class, orderSn);

            if (count != null && count > 0) {
                log.info("【MQ 回查】订单已落库，补发 COMMIT。orderSn={}", orderSn);
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                log.warn("【MQ 回查】订单未落库，执行 ROLLBACK。orderSn={}", orderSn);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            log.error("【MQ 回查】查询异常，返回 UNKNOWN 等待下次回查。orderSn={}", orderSn, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
