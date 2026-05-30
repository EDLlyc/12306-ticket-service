package com.ahu.ticket.service.impl;

import com.ahu.ticket.agent.plan.ToolObservation;
import com.ahu.ticket.entity.Train;
import com.ahu.ticket.order.OrderInventoryStateService;
import com.ahu.ticket.order.OrderTimeoutMessageService;
import com.ahu.ticket.order.RefundCompensationService;
import com.ahu.ticket.service.ITrainService;
import com.ahu.ticket.service.StockBucketService;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 工具类 (Function Calling)
 *
 * 面试亮点：让大模型从"纯聊天机器人"升级为"能操作数据库的智能体"。
 * 大模型通过分析用户意图，输出 JSON 格式的工具调用请求，
 * Java 代码执行真实数据库查询后，将结果封入 Prompt 交回给 LLM 总结。
 */
@Slf4j
@Component
public class TicketTools {
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT_OP");

    @Autowired
    private ITrainService trainService;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern ORDER_SN_PATTERN = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern TRAIN_NUMBER_PATTERN = Pattern.compile("(?i)^(G|D|C|Z|T|K|Y|L)\\d{1,4}$");
    private static final Pattern STATION_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z]{2,30}$");

    /**
     * 查票工具：Agent 调用此方法查询真实数据库中的车次和余票
     */
    @Tool("当用户询问特定日期从出发地到目的地的两地之间的列车余票或时刻表时调用此工具。请告诉用户最新的余票信息。")
    public String searchTrainTickets(
            @P("出发日期，格式 yyyy-MM-dd 例如 2024-05-01") String date,
            @P("出发城市名称") String fromStation,
            @P("到达城市名称") String toStation) {
        return observeSearchTrainTickets(date, fromStation, toStation).userMessage();
    }

    public ToolObservation observeSearchTrainTickets(String date, String fromStation, String toStation) {
        String dateError = validateDate(date);
        if (dateError != null) {
            return observation("searchTrainTickets", false, "INVALID_DATE", dateError, true, false,
                    Map.of("date", safeValue(date)));
        }
        String fromError = validateStation(fromStation, "出发地");
        if (fromError != null) {
            return observation("searchTrainTickets", false, "INVALID_FROM_STATION", fromError, true, false,
                    Map.of("fromStation", safeValue(fromStation)));
        }
        String toError = validateStation(toStation, "目的地");
        if (toError != null) {
            return observation("searchTrainTickets", false, "INVALID_TO_STATION", toError, true, false,
                    Map.of("toStation", safeValue(toStation)));
        }

        log.info("🤖 Agent 正在调用查票工具: {} 从 {} → {}", date, fromStation, toStation);

        // 真实查询数据库：按出发站和到达站模糊匹配
        List<Train> trains = trainService.list(
                new QueryWrapper<Train>()
                        .like("start_station", fromStation)
                        .like("end_station", toStation));

        if (trains.isEmpty()) {
            log.info("🤖 未找到匹配车次: {} → {}", fromStation, toStation);
            return observation("searchTrainTickets", false, "NO_MATCHED_TRAINS",
                    "很抱歉，" + date + " 从 " + fromStation + " 到 " + toStation
                            + " 暂无匹配的列车信息，建议您更换日期或中转站查询。",
                    true, false,
                    Map.of("date", date, "fromStation", fromStation, "toStation", toStation, "matchCount", 0));
        }

        // 格式化查询结果
        StringBuilder result = new StringBuilder();
        result.append("查询结果（").append(date).append(" ").append(fromStation)
                .append(" → ").append(toStation).append("）：\n");

        for (int i = 0; i < trains.size(); i++) {
            Train t = trains.get(i);
            result.append(String.format("%d. %s次列车，%s发车 → %s到达，余票: %d 张\n",
                    i + 1,
                    t.getTrainNumber(),
                    t.getStartTime() != null ? t.getStartTime().format(TIME_FORMAT) : "未知",
                    t.getEndTime() != null ? t.getEndTime().format(TIME_FORMAT) : "未知",
                    t.getStock() != null ? t.getStock() : 0));
        }

        log.info("🤖 查票工具返回 {} 条结果", trains.size());
        return observation("searchTrainTickets", true, "TRAINS_FOUND", result.toString(), false, false,
                Map.of("date", date, "fromStation", fromStation, "toStation", toStation, "matchCount", trains.size()));
    }

    @Tool("当用户想按出发地、目的地、日期直接买票，但没有明确给出车次号时调用此工具。你应该直接为用户选择一趟有票的合适车次并完成下单。")
    public String bookTicketByRoute(
            @P("出发日期，格式 yyyy-MM-dd，若用户说今天/明天，也要换算后传入") String date,
            @P("出发城市或车站名称") String fromStation,
            @P("到达城市或车站名称") String toStation,
            @P("当前登录的用户名") String username) {
        return observeBookTicketByRoute(date, fromStation, toStation, username).userMessage();
    }

    public ToolObservation observeBookTicketByRoute(String date, String fromStation, String toStation, String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("bookTicketByRoute", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }
        String dateError = validateDate(date);
        if (dateError != null) {
            return observation("bookTicketByRoute", false, "INVALID_DATE", dateError, true, false,
                    Map.of("date", safeValue(date)));
        }
        String fromError = validateStation(fromStation, "出发地");
        if (fromError != null) {
            return observation("bookTicketByRoute", false, "INVALID_FROM_STATION", fromError, true, false,
                    Map.of("fromStation", safeValue(fromStation)));
        }
        String toError = validateStation(toStation, "目的地");
        if (toError != null) {
            return observation("bookTicketByRoute", false, "INVALID_TO_STATION", toError, true, false,
                    Map.of("toStation", safeValue(toStation)));
        }

        log.info("🤖 Agent 正在调用按路线购票工具: {} {} → {}, username={}", date, fromStation, toStation, username);

        List<Train> candidateTrains = trainService.list(
                new QueryWrapper<Train>()
                        .like("start_station", fromStation)
                        .like("end_station", toStation)
                        .orderByAsc("start_time"));
        candidateTrains.removeIf(train -> {
            Integer availableStock = trainInventoryLedgerService.resolveAvailableStock(train);
            return availableStock == null || availableStock <= 0;
        });

        if (candidateTrains.isEmpty()) {
            return observation("bookTicketByRoute", false, "NO_DIRECT_TRAIN_AVAILABLE",
                    "很抱歉，" + date + " 从 " + fromStation + " 到 " + toStation + " 暂无可直接购买的有票车次。",
                    true, false,
                    Map.of("date", date, "fromStation", fromStation, "toStation", toStation));
        }

        Train selectedTrain = candidateTrains.stream()
                .min(Comparator.comparing(Train::getStartTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(candidateTrains.get(0));

        ToolObservation bookObservation = observeBookTicket(selectedTrain.getTrainNumber(), username);
        if (bookObservation.success()) {
            return observation("bookTicketByRoute", true, "ROUTE_BOOK_SUCCESS",
                    "已根据您的路线请求，自动为您选择车次 " + selectedTrain.getTrainNumber() + "。\n" + bookObservation.userMessage(),
                    true, false,
                    mergeData(bookObservation.data(),
                            Map.of("selectedTrainNumber", selectedTrain.getTrainNumber(), "date", date,
                                    "fromStation", fromStation, "toStation", toStation)));
        }
        return observation("bookTicketByRoute", false, "ROUTE_BOOK_FAILED",
                "已为您匹配到车次 " + selectedTrain.getTrainNumber() + "，但下单失败：\n" + bookObservation.userMessage(),
                true, bookObservation.retryable(),
                mergeData(bookObservation.data(),
                        Map.of("selectedTrainNumber", selectedTrain.getTrainNumber(), "date", date,
                                "fromStation", fromStation, "toStation", toStation, "nestedCode", bookObservation.code())));
    }

    // ================================================================
    // 工具 2：订单状态查询 (面试亮点：展示 Agent 多工具决策)
    // ================================================================

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private StockBucketService stockBucketService;

    @Autowired
    private OrderTimeoutMessageService orderTimeoutMessageService;

    @Autowired
    private RefundCompensationService refundCompensationService;

    @Autowired
    private OrderInventoryStateService orderInventoryStateService;

    @Autowired
    private TrainInventoryLedgerService trainInventoryLedgerService;

    /**
     * 查询订单状态：Agent 根据用户提供的订单号查询 t_order 表
     */
    @Tool("当用户询问自己某个订单的状态、是否购票成功、订单详情时调用此工具。")
    public String queryOrderStatus(
            @P("用户的订单号，格式为 UUID") String orderSn,
            @P("当前登录用户名") String username) {
        return observeQueryOrderStatus(orderSn, username).userMessage();
    }

    public ToolObservation observeQueryOrderStatus(String orderSn, String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("queryOrderStatus", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }
        String normalizedOrderSn = normalizeOrderSn(orderSn);
        if (normalizedOrderSn == null) {
            return observation("queryOrderStatus", false, "INVALID_ORDER_SN",
                    "订单号格式不合法，请提供标准 UUID（例如 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）。",
                    true, false, Map.of("orderSn", safeValue(orderSn)));
        }

        log.info("🤖 Agent 正在调用订单查询工具: orderSn={}, username={}", normalizedOrderSn, username);

        try {
            var results = jdbcTemplate.queryForList(
                    "SELECT order_sn, train_number, username, status FROM t_order WHERE order_sn = ? AND username = ?",
                    normalizedOrderSn, username);

            if (results.isEmpty()) {
                return observation("queryOrderStatus", false, "ORDER_NOT_FOUND",
                        "未找到您名下订单号为 " + normalizedOrderSn + " 的订单记录，请确认订单号是否正确。",
                        true, false, Map.of("orderSn", normalizedOrderSn));
            }

            var row = results.get(0);
            return observation("queryOrderStatus", true, "ORDER_STATUS_FOUND",
                    String.format("订单查询结果：\n订单号: %s\n车次: %s\n乘客: %s\n状态: %s",
                            row.get("order_sn"), row.get("train_number"),
                            row.get("username"), row.get("status")),
                    true, false,
                    Map.of(
                            "orderSn", String.valueOf(row.get("order_sn")),
                            "trainNumber", String.valueOf(row.get("train_number")),
                            "username", String.valueOf(row.get("username")),
                            "status", String.valueOf(row.get("status"))
                    ));

        } catch (Exception e) {
            log.error("🤖 订单查询异常: {}", e.getMessage());
            return observation("queryOrderStatus", false, "ORDER_QUERY_ERROR",
                    "订单查询失败，请稍后重试。", true, true,
                    Map.of("orderSn", normalizedOrderSn, "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    // ================================================================
    // 工具 3：取消订单 (面试亮点：展示 Agent 写操作 + 库存回补)
    // ================================================================

    /**
     * 取消订单：更新订单状态 + 回补 MySQL/Redis 双端库存
     *
     * 面试话术：Agent 不仅能查，还能写。大模型自动判断用户意图后
     * 选择调用此工具，实现"对话即操作"的智能体能力。
     *
     * 面试关键设计点：
     * 1. @Transactional 保证 MySQL 侧两步操作（改订单状态 + 回补库存）的原子性
     * 2. 执行顺序：先 MySQL（强一致事务保障），再 Redis（允许最终一致）
     * 3. 即使 Redis 回补失败，MySQL 数据是正确的，下次预热可自动恢复
     */
    @Transactional
    @Tool("当用户要求退票、取消订单、撤单或不想要票时调用。支持直接传订单号，也支持只传用户名来自动退最后一张票。")
    public String cancelOrder(
            @P("要退票的订单号 (选填，若用户提供了则必传)") String orderSn,
            @P("当前登录用户名 (必填，用于在未提供单号时自动查找最后一张票)") String username) {
        return observeCancelOrder(orderSn, username).userMessage();
    }

    @Transactional
    public ToolObservation observeCancelOrder(String orderSn, String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("cancelOrder", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }

        log.info("🤖 Agent 正在调用智能退票工具: orderSn={}, username={}", orderSn, username);

        try {
            String finalOrderSn = orderSn;

            // 1. 如果没传单号，或者传的是空/null，则根据用户名查最后一张有效订单
            if (finalOrderSn == null || finalOrderSn.isBlank() || "null".equalsIgnoreCase(finalOrderSn)) {
                log.info("🤖 订单号为空，尝试为用户 {} 查找最近一张可退订单...", username);
                var lastOrders = jdbcTemplate.queryForList(
                        "SELECT order_sn FROM t_order WHERE username = ? AND (status = 'SUCCESS' OR status = 'PENDING') ORDER BY id DESC LIMIT 1",
                        username);

                if (lastOrders.isEmpty()) {
                    audit("REFUND", "NOOP", username, null, null, "no_refundable_order");
                    return observation("cancelOrder", false, "NO_REFUNDABLE_ORDER",
                            "抱歉，未找到您（" + username + "）有任何可退的订单（需为已支付或排队中状态）。",
                            true, false, Map.of("username", username));
                }
                finalOrderSn = String.valueOf(lastOrders.get(0).get("order_sn"));
                log.info("🤖 自动匹配到最近订单: {}", finalOrderSn);
            } else {
                finalOrderSn = finalOrderSn.trim();
                if (!ORDER_SN_PATTERN.matcher(finalOrderSn).matches()) {
                    audit("REFUND", "DENY", username, finalOrderSn, null, "invalid_order_sn_format");
                    return observation("cancelOrder", false, "INVALID_ORDER_SN",
                            "订单号格式不合法，请提供标准 UUID（例如 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）。",
                            true, false, Map.of("orderSn", finalOrderSn));
                }
            }

            // 2. 执行退票逻辑
            var results = jdbcTemplate.queryForList(
                    "SELECT train_number, status FROM t_order WHERE order_sn = ? AND username = ?",
                    finalOrderSn, username);

            if (results.isEmpty()) {
                audit("REFUND", "DENY", username, finalOrderSn, null, "order_not_found");
                return observation("cancelOrder", false, "ORDER_NOT_FOUND",
                        "未找到您名下订单号为 " + finalOrderSn + " 的记录，请核对。",
                        true, false, Map.of("orderSn", finalOrderSn));
            }

            String status = String.valueOf(results.get(0).get("status"));
            if ("CANCELLED".equals(status)) {
                audit("REFUND", "NOOP", username, finalOrderSn, null, "already_cancelled");
                return observation("cancelOrder", false, "ALREADY_CANCELLED",
                        "订单 " + finalOrderSn + " 已经是取消状态，无需重复操作。",
                        true, false, Map.of("orderSn", finalOrderSn, "status", status));
            }
            if (!"SUCCESS".equals(status) && !"PENDING".equals(status)) {
                audit("REFUND", "DENY", username, finalOrderSn, null, "invalid_status_" + status);
                return observation("cancelOrder", false, "UNSUPPORTED_ORDER_STATUS",
                        "订单 " + finalOrderSn + " 当前状态为 " + status + "，暂不支持退票。",
                        true, false, Map.of("orderSn", finalOrderSn, "status", status));
            }

            String trainNumber = String.valueOf(results.get(0).get("train_number"));

            // 3. 幂等更新订单状态（并发下只允许一次成功）
            int updated = jdbcTemplate.update(
                    "UPDATE t_order SET status = 'CANCELLED', updated_at = ? WHERE order_sn = ? AND username = ? AND (status = 'SUCCESS' OR status = 'PENDING')",
                    LocalDateTime.now(),
                    finalOrderSn, username);
            if (updated == 0) {
                var latest = jdbcTemplate.queryForList(
                        "SELECT status FROM t_order WHERE order_sn = ? AND username = ?",
                        finalOrderSn, username);
                if (latest.isEmpty()) {
                    audit("REFUND", "DENY", username, finalOrderSn, trainNumber, "order_not_found_after_update");
                    return observation("cancelOrder", false, "ORDER_NOT_FOUND",
                            "未找到您名下订单号为 " + finalOrderSn + " 的记录，请核对。",
                            true, false, Map.of("orderSn", finalOrderSn));
                }
                String latestStatus = String.valueOf(latest.get(0).get("status"));
                if ("CANCELLED".equals(latestStatus)) {
                    audit("REFUND", "NOOP", username, finalOrderSn, trainNumber, "already_cancelled_concurrent");
                    return observation("cancelOrder", false, "ALREADY_CANCELLED",
                            "订单 " + finalOrderSn + " 已经是取消状态，无需重复操作。",
                            true, false, Map.of("orderSn", finalOrderSn, "status", latestStatus));
                }
                audit("REFUND", "DENY", username, finalOrderSn, trainNumber, "invalid_status_after_update_" + latestStatus);
                return observation("cancelOrder", false, "UNSUPPORTED_ORDER_STATUS",
                        "订单 " + finalOrderSn + " 当前状态为 " + latestStatus + "，暂不支持退票。",
                        true, false, Map.of("orderSn", finalOrderSn, "status", latestStatus));
            }

            // 4. 回补 MySQL 库存
            if ("SUCCESS".equals(status)) {
                if (!trainInventoryLedgerService.releaseSoldInventory(trainNumber)) {
                    throw new IllegalStateException("退票回补已售库存失败");
                }
            } else {
                if (!trainInventoryLedgerService.releaseLockedInventory(trainNumber)) {
                    throw new IllegalStateException("退票回补锁定库存失败");
                }
            }

            // 5. 标记 Redis 侧待补偿（由 MQ 异步回补并置位）
            orderInventoryStateService.markInventoryReleasePending(finalOrderSn);
            try {
                jdbcTemplate.update(
                        "UPDATE t_order SET refund_redis_compensated = 0 WHERE order_sn = ?",
                        finalOrderSn
                );
            } catch (Exception ignore) {
                // 老库未迁移该字段时跳过，补偿消费者会按兼容模式处理
            }

            // 6. 发送异步补偿消息（失败可重试）
            refundCompensationService.publishAfterCommit(trainNumber, finalOrderSn);

            log.info("🤖 智能退票成功: orderSn={}, trainNumber={}", finalOrderSn, trainNumber);
            audit("REFUND", "SUCCESS", username, finalOrderSn, trainNumber, "ok");
            return observation("cancelOrder", true, "REFUND_SUCCESS",
                    "✅ 退票成功！已为您取消订单 " + finalOrderSn + " (车次 " + trainNumber + ")，库存回补处理中。",
                    true, false, Map.of("orderSn", finalOrderSn, "trainNumber", trainNumber, "username", username));

        } catch (Exception e) {
            log.error("🤖 智能退票异常: {}", e.getMessage());
            audit("REFUND", "ERROR", username, orderSn, null, "exception_" + abbreviateReason(e.getClass().getSimpleName()));
            return observation("cancelOrder", false, "REFUND_ERROR",
                    "❌ 退票失败，系统异常，请稍后重试。", true, true,
                    Map.of("orderSn", safeValue(orderSn), "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    @Transactional
    @Tool("当用户按车次号要求退票时调用。会优先退该用户该车次最近一张可退订单，避免误退其他车次。")
    public String cancelOrderByTrainNumber(
            @P("要退票的车次号，例如 G1001") String trainNumber,
            @P("当前登录用户名 (必填)") String username) {
        return observeCancelOrderByTrainNumber(trainNumber, username).userMessage();
    }

    @Transactional
    public ToolObservation observeCancelOrderByTrainNumber(String trainNumber, String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("cancelOrderByTrainNumber", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }

        log.info("🤖 Agent 正在调用按车次退票工具: trainNumber={}, username={}", trainNumber, username);

        try {
            String normalizedTrainNumber = normalizeTrainNumber(trainNumber);
            if (normalizedTrainNumber == null) {
                audit("BATCH_REFUND_TRAIN", "DENY", username, null, trainNumber, "invalid_train_number");
                return observation("cancelOrderByTrainNumber", false, "INVALID_TRAIN_NUMBER",
                        "请提供合法车次号，例如 G1001。", true, false,
                        Map.of("trainNumber", safeValue(trainNumber)));
            }

            var candidateOrders = jdbcTemplate.queryForList(
                    "SELECT order_sn FROM t_order " +
                            "WHERE username = ? AND train_number = ? AND (status = 'SUCCESS' OR status = 'PENDING') " +
                            "ORDER BY id DESC LIMIT 2",
                    username, normalizedTrainNumber);

            if (candidateOrders.isEmpty()) {
                audit("BATCH_REFUND_TRAIN", "NOOP", username, null, normalizedTrainNumber, "no_refundable_order");
                return observation("cancelOrderByTrainNumber", false, "NO_REFUNDABLE_ORDER_FOR_TRAIN",
                        "未找到您在车次 " + normalizedTrainNumber + " 下可退的订单（需为已支付或排队中状态）。",
                        true, false, Map.of("trainNumber", normalizedTrainNumber));
            }

            if (candidateOrders.size() > 1) {
                audit("BATCH_REFUND_TRAIN", "DENY", username, null, normalizedTrainNumber, "multiple_orders_need_order_sn");
                return observation("cancelOrderByTrainNumber", false, "AMBIGUOUS_MULTIPLE_ORDERS",
                        "您在车次 " + normalizedTrainNumber + " 下存在多张可退订单。为避免误退，请提供订单号后再退票。",
                        true, false, Map.of("trainNumber", normalizedTrainNumber, "candidateCount", candidateOrders.size()));
            }

            String orderSn = String.valueOf(candidateOrders.get(0).get("order_sn"));
            ToolObservation observation = observeCancelOrder(orderSn, username);
            return observation("cancelOrderByTrainNumber", observation.success(), observation.code(),
                    observation.userMessage(), observation.terminal(), observation.retryable(),
                    mergeData(observation.data(), Map.of("trainNumber", normalizedTrainNumber)));
        } catch (Exception e) {
            log.error("🤖 按车次退票异常: {}", e.getMessage());
            audit("BATCH_REFUND_TRAIN", "ERROR", username, null, trainNumber, "exception_" + abbreviateReason(e.getClass().getSimpleName()));
            return observation("cancelOrderByTrainNumber", false, "TRAIN_REFUND_ERROR",
                    "❌ 退票失败，系统异常，请稍后重试。", true, true,
                    Map.of("trainNumber", safeValue(trainNumber), "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    @Transactional
    @Tool("当用户明确要求将某一车次下的订单都退掉时调用。")
    public String cancelAllOrdersByTrainNumber(
            @P("要批量退票的车次号，例如 G1001") String trainNumber,
            @P("当前登录用户名 (必填)") String username) {
        return observeCancelAllOrdersByTrainNumber(trainNumber, username).userMessage();
    }

    @Transactional
    public ToolObservation observeCancelAllOrdersByTrainNumber(String trainNumber, String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("cancelAllOrdersByTrainNumber", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }

        log.info("🤖 Agent 正在调用按车次批量退票工具: trainNumber={}, username={}", trainNumber, username);

        try {
            String normalizedTrainNumber = normalizeTrainNumber(trainNumber);
            if (normalizedTrainNumber == null) {
                audit("BATCH_REFUND_TRAIN", "DENY", username, null, trainNumber, "invalid_train_number");
                return observation("cancelAllOrdersByTrainNumber", false, "INVALID_TRAIN_NUMBER",
                        "请提供合法车次号，例如 G1001。", true, false,
                        Map.of("trainNumber", safeValue(trainNumber)));
            }
            var candidateOrders = jdbcTemplate.queryForList(
                    "SELECT order_sn, train_number, status FROM t_order " +
                            "WHERE username = ? AND train_number = ? AND (status = 'SUCCESS' OR status = 'PENDING') " +
                            "ORDER BY id DESC",
                    username, normalizedTrainNumber);

            if (candidateOrders.isEmpty()) {
                audit("BATCH_REFUND_TRAIN", "NOOP", username, null, normalizedTrainNumber, "no_refundable_order");
                return observation("cancelAllOrdersByTrainNumber", false, "NO_REFUNDABLE_ORDER_FOR_TRAIN",
                        "未找到您在车次 " + normalizedTrainNumber + " 下可退的订单（需为已支付或排队中状态）。",
                        true, false, Map.of("trainNumber", normalizedTrainNumber));
            }

            int successCount = 0;
            for (var row : candidateOrders) {
                String orderSn = String.valueOf(row.get("order_sn"));
                String rowTrainNumber = String.valueOf(row.get("train_number"));
                String rowStatus = String.valueOf(row.get("status"));
                if (cancelSingleOrder(orderSn, rowTrainNumber, username, rowStatus)) {
                    successCount++;
                }
            }

            if (successCount == 0) {
                audit("BATCH_REFUND_TRAIN", "NOOP", username, null, normalizedTrainNumber, "no_success");
                return observation("cancelAllOrdersByTrainNumber", false, "BATCH_REFUND_NO_SUCCESS",
                        "未能完成批量退票，可能相关订单状态已变化，请刷新后重试。",
                        true, true, Map.of("trainNumber", normalizedTrainNumber));
            }

            audit("BATCH_REFUND_TRAIN", "SUCCESS", username, null, normalizedTrainNumber, "count=" + successCount);
            return observation("cancelAllOrdersByTrainNumber", true, "BATCH_REFUND_TRAIN_SUCCESS",
                    "✅ 已为您批量退票成功 " + successCount + " 笔（车次 " + normalizedTrainNumber + "），库存回补处理中。",
                    true, false, Map.of("trainNumber", normalizedTrainNumber, "successCount", successCount));
        } catch (Exception e) {
            log.error("🤖 按车次批量退票异常: {}", e.getMessage());
            audit("BATCH_REFUND_TRAIN", "ERROR", username, null, trainNumber, "exception_" + abbreviateReason(e.getClass().getSimpleName()));
            return observation("cancelAllOrdersByTrainNumber", false, "BATCH_REFUND_TRAIN_ERROR",
                    "❌ 批量退票失败，系统异常，请稍后重试。", true, true,
                    Map.of("trainNumber", safeValue(trainNumber), "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    @Transactional
    @Tool("当用户明确要求把自己可退的订单都退掉时调用。")
    public String cancelAllOrders(
            @P("当前登录用户名 (必填)") String username) {
        return observeCancelAllOrders(username).userMessage();
    }

    @Transactional
    public ToolObservation observeCancelAllOrders(String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("cancelAllOrders", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }

        log.info("🤖 Agent 正在调用全量退票工具: username={}", username);

        try {
            var candidateOrders = jdbcTemplate.queryForList(
                    "SELECT order_sn, train_number, status FROM t_order " +
                            "WHERE username = ? AND (status = 'SUCCESS' OR status = 'PENDING') " +
                            "ORDER BY id DESC",
                    username);

            if (candidateOrders.isEmpty()) {
                audit("BATCH_REFUND_ALL", "NOOP", username, null, null, "no_refundable_order");
                return observation("cancelAllOrders", false, "NO_REFUNDABLE_ORDER",
                        "抱歉，未找到您（" + username + "）任何可退订单（需为已支付或排队中状态）。",
                        true, false, Map.of("username", username));
            }

            int successCount = 0;
            for (var row : candidateOrders) {
                String orderSn = String.valueOf(row.get("order_sn"));
                String rowTrainNumber = String.valueOf(row.get("train_number"));
                String rowStatus = String.valueOf(row.get("status"));
                if (cancelSingleOrder(orderSn, rowTrainNumber, username, rowStatus)) {
                    successCount++;
                }
            }

            if (successCount == 0) {
                audit("BATCH_REFUND_ALL", "NOOP", username, null, null, "no_success");
                return observation("cancelAllOrders", false, "BATCH_REFUND_NO_SUCCESS",
                        "未能完成批量退票，可能相关订单状态已变化，请刷新后重试。",
                        true, true, Map.of("username", username));
            }

            audit("BATCH_REFUND_ALL", "SUCCESS", username, null, null, "count=" + successCount);
            return observation("cancelAllOrders", true, "BATCH_REFUND_ALL_SUCCESS",
                    "✅ 已为您批量退票成功 " + successCount + " 笔，库存回补处理中。",
                    true, false, Map.of("username", username, "successCount", successCount));
        } catch (Exception e) {
            log.error("🤖 全量退票异常: {}", e.getMessage());
            audit("BATCH_REFUND_ALL", "ERROR", username, null, null, "exception_" + abbreviateReason(e.getClass().getSimpleName()));
            return observation("cancelAllOrders", false, "BATCH_REFUND_ALL_ERROR",
                    "❌ 批量退票失败，系统异常，请稍后重试。", true, true,
                    Map.of("username", username, "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    private boolean cancelSingleOrder(String orderSn, String trainNumber, String username, String originalStatus) {
        int updated = jdbcTemplate.update(
                "UPDATE t_order SET status = 'CANCELLED', updated_at = ? WHERE order_sn = ? AND username = ? AND (status = 'SUCCESS' OR status = 'PENDING')",
                LocalDateTime.now(), orderSn, username);
        if (updated == 0) {
            return false;
        }

        if ("SUCCESS".equals(originalStatus)) {
            if (!trainInventoryLedgerService.releaseSoldInventory(trainNumber)) {
                throw new IllegalStateException("批量退票回补已售库存失败");
            }
        } else {
            if (!trainInventoryLedgerService.releaseLockedInventory(trainNumber)) {
                throw new IllegalStateException("批量退票回补锁定库存失败");
            }
        }

        orderInventoryStateService.markInventoryReleasePending(orderSn);
        try {
            jdbcTemplate.update(
                    "UPDATE t_order SET refund_redis_compensated = 0 WHERE order_sn = ?",
                    orderSn
            );
        } catch (Exception ignore) {
            // 老库未迁移该字段时跳过
        }

        refundCompensationService.publishAfterCommit(trainNumber, orderSn);
        return true;
    }

    // ================================================================
    // 工具 4：购票工具 (面试亮点：Agent 能真正买票！)
    // ================================================================

    /**
     * 购票工具：Agent 根据用户的自然语言购票请求，执行真实扣减库存 + 订单落库
     *
     * 面试话术：Agent 不只是查询助手，还能执行写操作。
     * 大模型解析用户意图后自动提取车次号和用户名，调用此工具完成购票闭环。
     */
    @Transactional
    @Tool("当用户要求购买车票、买票、订票、预订某个车次时调用此工具。需要车次号和用户名。")
    public String bookTicket(
            @P("要购买的车次号，例如 G7236") String trainNumber,
            @P("当前登录的用户名") String username) {
        return observeBookTicket(trainNumber, username).userMessage();
    }

    @Transactional
    public ToolObservation observeBookTicket(String trainNumber, String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("bookTicket", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }
        String normalizedTrainNumber = normalizeTrainNumber(trainNumber);
        if (normalizedTrainNumber == null) {
            return observation("bookTicket", false, "INVALID_TRAIN_NUMBER",
                    "车次号格式不合法，请提供如 G7236 的车次号。", true, false,
                    Map.of("trainNumber", safeValue(trainNumber)));
        }

        log.info("🤖 Agent 正在调用购票工具: trainNumber={}, username={}", normalizedTrainNumber, username);

        String orderSn = UUID.randomUUID().toString();
        boolean reserved = false;

        try {
            // 0. 幂等拦截：同一用户同一车次已有进行中/成功订单，直接拦截重复购票
            var existingOrders = jdbcTemplate.queryForList(
                    "SELECT order_sn, status FROM t_order " +
                            "WHERE username = ? AND train_number = ? AND (status = 'SUCCESS' OR status = 'PENDING' OR status = 'CREATED' OR status = 'PROCESSING') " +
                            "ORDER BY id DESC LIMIT 1",
                    username, normalizedTrainNumber);
            if (!existingOrders.isEmpty()) {
                String existingOrderSn = String.valueOf(existingOrders.get(0).get("order_sn"));
                String existingStatus = String.valueOf(existingOrders.get(0).get("status"));
                audit("BOOK", "NOOP", username, existingOrderSn, normalizedTrainNumber, "duplicate_order_status_" + existingStatus);
                return observation("bookTicket", false, "DUPLICATE_ORDER",
                        "检测到重复购票请求：您在车次 " + normalizedTrainNumber + " 已有订单 " + existingOrderSn +
                                "（状态：" + existingStatus + "）。如需重购请先退票。",
                        true, false,
                        Map.of("trainNumber", normalizedTrainNumber, "orderSn", existingOrderSn, "status", existingStatus));
            }

            // 1. 验证车次是否存在
            Train train = trainService.getOne(
                    new QueryWrapper<Train>().eq("train_number", normalizedTrainNumber));
            if (train == null) {
                audit("BOOK", "DENY", username, null, normalizedTrainNumber, "train_not_found");
                return observation("bookTicket", false, "TRAIN_NOT_FOUND",
                        "很抱歉，车次 " + normalizedTrainNumber + " 不存在，请确认车次号是否正确。",
                        true, false, Map.of("trainNumber", normalizedTrainNumber));
            }

            // 2. Redis 分桶原子扣减库存
            StockBucketService.ReserveResult reserveResult = stockBucketService.reserve(normalizedTrainNumber, orderSn);
            if (!reserveResult.isSuccess()) {
                if ("NOT_INITIALIZED".equals(reserveResult.getCode())) {
                    audit("BOOK", "DENY", username, orderSn, normalizedTrainNumber, "stock_not_initialized");
                    return observation("bookTicket", false, "STOCK_NOT_INITIALIZED",
                            "车次 " + normalizedTrainNumber + " 尚未完成库存预热，请先在购票大厅初始化库存后再试。",
                            true, true, Map.of("trainNumber", normalizedTrainNumber, "orderSn", orderSn));
                }
                audit("BOOK", "DENY", username, orderSn, normalizedTrainNumber, "out_of_stock");
                return observation("bookTicket", false, "OUT_OF_STOCK",
                        "很抱歉，车次 " + normalizedTrainNumber + " 的余票已售完，建议您选择其他车次。",
                        true, false, Map.of("trainNumber", normalizedTrainNumber, "orderSn", orderSn));
            }
            reserved = true;

            // 3. 生成唯一订单号并落库
            LocalDateTime now = LocalDateTime.now();
            orderInventoryStateService.insertPendingOrder(orderSn, normalizedTrainNumber, username, now);

            // 4. 同步扣减 MySQL 库存
            boolean dbOk = trainInventoryLedgerService.lockAvailableInventory(normalizedTrainNumber);
            if (!dbOk) {
                boolean released = stockBucketService.releaseByOrderSn(normalizedTrainNumber, orderSn);
                jdbcTemplate.update(
                        "UPDATE t_order SET status = 'FAILED', updated_at = ? WHERE order_sn = ? AND username = ?",
                        LocalDateTime.now(), orderSn, username
                );
                if (released) {
                    orderInventoryStateService.markInventoryReleased(orderSn);
                }
                audit("BOOK", "DENY", username, orderSn, normalizedTrainNumber, "db_stock_race");
                return observation("bookTicket", false, "DB_STOCK_RACE",
                        "很抱歉，当前余票变动较快，请稍后重试。",
                        true, true, Map.of("trainNumber", normalizedTrainNumber, "orderSn", orderSn));
            }
            orderInventoryStateService.markInventoryConfirmed(orderSn);
            reserved = false;
            try {
                orderTimeoutMessageService.sendOrderTimeoutMessage(orderSn);
            } catch (Exception e) {
                log.warn("购票成功后发送延迟关单消息失败 orderSn={}, error={}", orderSn, e.getMessage());
            }

            log.info("🤖 购票成功: orderSn={}, trainNumber={}, username={}", orderSn, normalizedTrainNumber, username);
            audit("BOOK", "SUCCESS", username, orderSn, normalizedTrainNumber, "ok");
            return observation("bookTicket", true, "BOOK_SUCCESS",
                    String.format("🎉 下单成功！\n订单号: %s\n车次: %s（%s → %s）\n乘客: %s\n状态: 待支付\n\n请在 15 分钟内完成支付，超时系统将自动取消订单。",
                            orderSn, normalizedTrainNumber, train.getStartStation(), train.getEndStation(), username),
                    true, false,
                    Map.of("orderSn", orderSn, "trainNumber", normalizedTrainNumber,
                            "startStation", train.getStartStation(), "endStation", train.getEndStation(),
                            "username", username, "status", "PENDING"));

        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (reserved) {
                safeReleaseReserve(normalizedTrainNumber, orderSn);
            }
            audit("BOOK", "NOOP", username, orderSn, normalizedTrainNumber, "duplicate_key");
            return observation("bookTicket", false, "DUPLICATE_ORDER",
                    "检测到重复订单，请勿重复购票。", true, false,
                    Map.of("orderSn", orderSn, "trainNumber", normalizedTrainNumber));
        } catch (Exception e) {
            if (reserved) {
                if (safeReleaseReserve(normalizedTrainNumber, orderSn)) {
                    orderInventoryStateService.markInventoryReleased(orderSn);
                }
                jdbcTemplate.update(
                        "UPDATE t_order SET status = 'FAILED', updated_at = ? WHERE order_sn = ? AND status = 'PENDING'",
                        LocalDateTime.now(), orderSn
                );
            }
            log.error("🤖 购票异常: {}", e.getMessage());
            audit("BOOK", "ERROR", username, orderSn, normalizedTrainNumber, "exception_" + abbreviateReason(e.getClass().getSimpleName()));
            return observation("bookTicket", false, "BOOK_ERROR",
                    "购票操作失败，请稍后重试或联系人工客服。错误信息：" + e.getMessage(),
                    true, true,
                    Map.of("orderSn", orderSn, "trainNumber", normalizedTrainNumber,
                            "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    // ================================================================
    // 工具 5：查询我的所有订单 (面试亮点：Agent 个性化服务)
    // ================================================================

    /**
     * 查询用户的所有订单：Agent 根据用户名查询该用户的全部订单记录
     */
    @Tool("当用户询问自己买了哪些票、我的订单列表、我的购票记录时调用此工具。")
    public String queryMyOrders(
            @P("当前登录的用户名") String username) {
        return observeQueryMyOrders(username).userMessage();
    }

    public ToolObservation observeQueryMyOrders(String username) {
        String usernameError = validateUsername(username);
        if (usernameError != null) {
            return observation("queryMyOrders", false, "INVALID_USERNAME", usernameError, true, false,
                    Map.of("username", safeValue(username)));
        }

        log.info("🤖 Agent 正在调用用户订单查询工具: username={}", username);

        try {
            var results = jdbcTemplate.queryForList(
                    "SELECT order_sn, train_number, username, status FROM t_order WHERE username = ? ORDER BY order_sn DESC",
                    username);

            if (results.isEmpty()) {
                return observation("queryMyOrders", false, "NO_ORDERS",
                        "您（" + username + "）暂无任何订单记录。", true, false,
                        Map.of("username", username, "orderCount", 0));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("您（").append(username).append("）共有 ").append(results.size()).append(" 条订单记录：\n\n");

            for (int i = 0; i < results.size(); i++) {
                var row = results.get(i);
                sb.append(String.format("%d. 订单号: %s | 车次: %s | 状态: %s\n",
                        i + 1, row.get("order_sn"), row.get("train_number"), row.get("status")));
            }

            return observation("queryMyOrders", true, "ORDERS_FOUND", sb.toString(), true, false,
                    Map.of("username", username, "orderCount", results.size()));

        } catch (Exception e) {
            log.error("🤖 用户订单查询异常: {}", e.getMessage());
            return observation("queryMyOrders", false, "QUERY_MY_ORDERS_ERROR",
                    "订单查询失败，请稍后重试。", true, true,
                    Map.of("username", username, "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    private ToolObservation observation(String toolName, boolean success, String code, String userMessage,
                                        boolean terminal, boolean retryable, Map<String, Object> data) {
        return ToolObservation.of(toolName, success, code, userMessage, data, terminal, retryable);
    }

    private Map<String, Object> mergeData(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) {
            merged.putAll(left);
        }
        if (right != null) {
            merged.putAll(right);
        }
        return merged;
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String validateUsername(String username) {
        if (username == null || username.isBlank()) {
            return "当前登录身份无效，请先登录后再操作。";
        }
        if (username.length() > 64) {
            return "用户名不合法，请重新登录后再试。";
        }
        return null;
    }

    private String normalizeTrainNumber(String trainNumber) {
        if (trainNumber == null || trainNumber.isBlank()) {
            return null;
        }
        String normalized = trainNumber.trim().toUpperCase(Locale.ROOT);
        if (!TRAIN_NUMBER_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private String normalizeOrderSn(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            return null;
        }
        String normalized = orderSn.trim();
        if (!ORDER_SN_PATTERN.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private String validateDate(String date) {
        if (date == null || date.isBlank()) {
            return "请提供出发日期，格式如 2026-04-21。";
        }
        try {
            LocalDate.parse(date.trim());
            return null;
        } catch (Exception e) {
            return "日期格式不合法，请使用 yyyy-MM-dd（例如 2026-04-21）。";
        }
    }

    private String validateStation(String station, String label) {
        if (station == null || station.isBlank()) {
            return label + "不能为空。";
        }
        String normalized = station.trim();
        if (!STATION_PATTERN.matcher(normalized).matches()) {
            return label + "格式不合法，请使用中文站名或英文站名（2-30字符）。";
        }
        return null;
    }

    private boolean safeReleaseReserve(String trainNumber, String orderSn) {
        try {
            return stockBucketService.releaseByOrderSn(trainNumber, orderSn);
        } catch (Exception ex) {
            log.warn("购票异常回滚库存失败 trainNumber={}, orderSn={}, error={}",
                    trainNumber, orderSn, ex.getMessage());
            return false;
        }
    }

    private void audit(String action, String result, String username, String orderSn, String trainNumber, String reason) {
        String traceId = MDC.get("traceId");
        auditLog.info("audit action={} result={} username={} orderSn={} trainNumber={} reason={} traceId={}",
                safeField(action),
                safeField(result),
                safeField(username),
                safeField(orderSn),
                safeField(trainNumber),
                safeField(reason),
                safeField(traceId));
    }

    private String safeField(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replaceAll("[\\r\\n\\t]", "_");
    }

    private String abbreviateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.length() <= 40 ? reason : reason.substring(0, 40);
    }
}
