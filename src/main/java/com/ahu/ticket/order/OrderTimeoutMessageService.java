package com.ahu.ticket.order;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderTimeoutMessageService {

    public static final String ORDER_TIMEOUT_TOPIC = "ticket-order-timeout-topic";

    private final RocketMQTemplate rocketMQTemplate;
    private final int paymentTimeoutMinutes;

    public OrderTimeoutMessageService(
            RocketMQTemplate rocketMQTemplate,
            @Value("${app.order.payment-timeout-minutes:15}") int paymentTimeoutMinutes) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.paymentTimeoutMinutes = paymentTimeoutMinutes;
    }

    public void sendOrderTimeoutMessage(String orderSn) {
        long delaySeconds = Math.max(1L, paymentTimeoutMinutes * 60L);
        SendResult result = rocketMQTemplate.syncSendDelayTimeSeconds(
                ORDER_TIMEOUT_TOPIC,
                orderSn,
                delaySeconds
        );
        log.info("已发送订单超时延迟消息 orderSn={}, delaySeconds={}, sendStatus={}",
                orderSn, delaySeconds, result != null ? result.getSendStatus() : "UNKNOWN");
    }
}
