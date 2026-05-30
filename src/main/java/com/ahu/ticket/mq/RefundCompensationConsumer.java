package com.ahu.ticket.mq;

import com.ahu.ticket.order.RefundCompensationService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mq", name = "consumers-enabled", havingValue = "true")
@RocketMQMessageListener(topic = "ticket-refund-topic", consumerGroup = "refund-compensate-group")
public class RefundCompensationConsumer implements RocketMQListener<String> {

    private final RefundCompensationService refundCompensationService;

    public RefundCompensationConsumer(RefundCompensationService refundCompensationService) {
        this.refundCompensationService = refundCompensationService;
    }

    @Override
    public void onMessage(String message) {
        String[] parts = message.split(",");
        String trainNumber = parts[0];
        String orderSn = parts[1];

        try {
            RefundCompensationService.CompensationOutcome outcome = refundCompensationService.process(trainNumber, orderSn);
            if (outcome.isCompensated()) {
                log.info("[退票补偿] Redis 回补成功。orderSn={}", orderSn);
            } else {
                log.info("[退票补偿] 跳过处理。orderSn={}, reason={}", orderSn, outcome.getCode());
            }
        } catch (Exception e) {
            log.error("[退票补偿] 处理失败，交给 RocketMQ 重试。orderSn={}", orderSn, e);
            throw e;
        }
    }
}
