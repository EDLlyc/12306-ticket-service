package com.ahu.ticket.order;

import com.ahu.ticket.service.impl.TicketTools;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OrderPaymentService {

    private final JdbcTemplate jdbcTemplate;
    private final TicketTools ticketTools;
    private final TrainInventoryLedgerService trainInventoryLedgerService;
    private final int paymentTimeoutMinutes;

    public OrderPaymentService(
            JdbcTemplate jdbcTemplate,
            TicketTools ticketTools,
            TrainInventoryLedgerService trainInventoryLedgerService,
            @Value("${app.order.payment-timeout-minutes:15}") int paymentTimeoutMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.ticketTools = ticketTools;
        this.trainInventoryLedgerService = trainInventoryLedgerService;
        this.paymentTimeoutMinutes = paymentTimeoutMinutes;
    }

    @Transactional
    public String payOrder(String orderSn, String username) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT order_sn, train_number, username, status, created_at, paid_at FROM t_order WHERE order_sn = ? AND username = ?",
                orderSn, username);

        if (rows.isEmpty()) {
            return "未找到您名下订单号为 " + orderSn + " 的订单记录，请确认订单号是否正确。";
        }

        Map<String, Object> order = rows.get(0);
        String status = String.valueOf(order.get("status"));

        if ("SUCCESS".equals(status)) {
            return "订单 " + orderSn + " 已支付成功，请勿重复支付。";
        }
        if ("CANCELLED".equals(status)) {
            return "订单 " + orderSn + " 已取消，无法继续支付。";
        }
        if ("FAILED".equals(status)) {
            return "订单 " + orderSn + " 下单失败，无法支付。";
        }
        if ("CREATED".equals(status) || "PROCESSING".equals(status)) {
            return "订单 " + orderSn + " 仍在处理中，请稍后刷新后再支付。";
        }
        if (!"PENDING".equals(status)) {
            return "订单 " + orderSn + " 当前状态为 " + status + "，暂不支持支付。";
        }

        LocalDateTime createdAt = toLocalDateTime(order.get("created_at"));
        if (createdAt == null) {
            return "订单 " + orderSn + " 创建时间异常，请稍后刷新后重试。";
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = createdAt.plusMinutes(paymentTimeoutMinutes);
        log.info("支付校验 orderSn={}, createdAt={}, expireAt={}, now={}", orderSn, createdAt, expireAt, now);
        if (expireAt.isBefore(now)) {
            ticketTools.cancelOrder(orderSn, username);
            return "订单 " + orderSn + " 已超过 " + paymentTimeoutMinutes + " 分钟未支付，系统已自动取消。";
        }

        int updated = jdbcTemplate.update(
                "UPDATE t_order SET status = 'SUCCESS', paid_at = ?, updated_at = ? WHERE order_sn = ? AND username = ? AND status = 'PENDING'",
                now, now, orderSn, username);
        if (updated == 0) {
            return "订单 " + orderSn + " 支付失败，请刷新后重试。";
        }

        String trainNumber = String.valueOf(order.get("train_number"));
        boolean ledgerUpdated = trainInventoryLedgerService.confirmLockedInventoryAsSold(trainNumber);
        if (!ledgerUpdated) {
            throw new IllegalStateException("支付成功但库存账本从 locked 转 sold 失败");
        }
        log.info("模拟支付成功 orderSn={}, username={}, trainNumber={}", orderSn, username, trainNumber);
        return "✅ 支付成功！订单 " + orderSn + " 已完成支付，车次 " + trainNumber + " 已出票。";
    }

    @Scheduled(fixedDelayString = "${app.order.timeout-scan-delay-ms:60000}")
    public void cancelExpiredUnpaidOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(paymentTimeoutMinutes);
        List<Map<String, Object>> expiredOrders = jdbcTemplate.queryForList(
                "SELECT order_sn, username FROM t_order WHERE status = 'PENDING' AND created_at <= ? ORDER BY created_at ASC LIMIT 100",
                cutoff);

        if (expiredOrders.isEmpty()) {
            return;
        }

        log.info("检测到 {} 笔超时未支付订单，开始执行定时兜底取消。", expiredOrders.size());
        for (Map<String, Object> order : expiredOrders) {
            String orderSn = String.valueOf(order.get("order_sn"));
            String username = String.valueOf(order.get("username"));
            try {
                String result = ticketTools.cancelOrder(orderSn, username);
                log.info("自动取消超时订单 orderSn={}, result={}", orderSn, result);
            } catch (Exception e) {
                log.warn("自动取消超时订单失败 orderSn={}, err={}", orderSn, e.getMessage());
            }
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
