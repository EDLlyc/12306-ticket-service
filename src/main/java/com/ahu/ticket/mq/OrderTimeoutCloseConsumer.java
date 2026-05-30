package com.ahu.ticket.mq;

import com.ahu.ticket.order.OrderTimeoutMessageService;
import com.ahu.ticket.service.impl.TicketTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "consumers-enabled", havingValue = "true")
@RocketMQMessageListener(
        topic = OrderTimeoutMessageService.ORDER_TIMEOUT_TOPIC,
        consumerGroup = "order-timeout-close-group")
public class OrderTimeoutCloseConsumer implements RocketMQListener<String> {

    private final JdbcTemplate jdbcTemplate;
    private final TicketTools ticketTools;
    private final int paymentTimeoutMinutes;

    public OrderTimeoutCloseConsumer(
            JdbcTemplate jdbcTemplate,
            TicketTools ticketTools,
            @Value("${app.order.payment-timeout-minutes:15}") int paymentTimeoutMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.ticketTools = ticketTools;
        this.paymentTimeoutMinutes = paymentTimeoutMinutes;
    }

    @Override
    public void onMessage(String orderSn) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT username, status, created_at FROM t_order WHERE order_sn = ?",
                    orderSn);
            if (rows.isEmpty()) {
                log.warn("[延迟关单] 订单不存在，跳过。orderSn={}", orderSn);
                return;
            }

            Map<String, Object> row = rows.get(0);
            String username = String.valueOf(row.get("username"));
            String status = String.valueOf(row.get("status"));
            if (!"PENDING".equals(status)) {
                log.info("[延迟关单] 订单无需取消，当前状态={}，orderSn={}", status, orderSn);
                return;
            }

            LocalDateTime createdAt = toLocalDateTime(row.get("created_at"));
            if (createdAt == null) {
                log.warn("[延迟关单] 订单创建时间异常，跳过。orderSn={}", orderSn);
                return;
            }
            LocalDateTime expireAt = createdAt.plusMinutes(paymentTimeoutMinutes);
            if (expireAt.isAfter(LocalDateTime.now())) {
                log.info("[延迟关单] 订单尚未达到超时点，跳过。orderSn={}, expireAt={}", orderSn, expireAt);
                return;
            }

            String result = ticketTools.cancelOrder(orderSn, username);
            log.info("[延迟关单] 自动取消完成 orderSn={}, result={}", orderSn, result);
        } catch (Exception e) {
            log.error("[延迟关单] 处理失败，交给 RocketMQ 重试。orderSn={}", orderSn, e);
            throw e;
        }
    }

    private LocalDateTime toLocalDateTime(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (raw instanceof java.util.Date date) {
            return new java.sql.Timestamp(date.getTime()).toLocalDateTime();
        }
        return null;
    }
}
