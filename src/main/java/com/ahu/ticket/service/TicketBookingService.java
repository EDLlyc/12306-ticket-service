package com.ahu.ticket.service;

import com.ahu.ticket.agent.plan.ToolObservation;
import com.ahu.ticket.entity.Train;
import com.ahu.ticket.order.OrderInventoryStateService;
import com.ahu.ticket.order.OrderTimeoutMessageService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class TicketBookingService {

    private final ITrainService trainService;
    private final StockBucketService stockBucketService;
    private final TrainInventoryLedgerService trainInventoryLedgerService;
    private final OrderInventoryStateService orderInventoryStateService;
    private final OrderTimeoutMessageService orderTimeoutMessageService;
    private final JdbcTemplate jdbcTemplate;

    public TicketBookingService(ITrainService trainService,
                                StockBucketService stockBucketService,
                                TrainInventoryLedgerService trainInventoryLedgerService,
                                OrderInventoryStateService orderInventoryStateService,
                                OrderTimeoutMessageService orderTimeoutMessageService,
                                JdbcTemplate jdbcTemplate) {
        this.trainService = trainService;
        this.stockBucketService = stockBucketService;
        this.trainInventoryLedgerService = trainInventoryLedgerService;
        this.orderInventoryStateService = orderInventoryStateService;
        this.orderTimeoutMessageService = orderTimeoutMessageService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public ToolObservation bookTicket(String trainNumber, String username) {
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

        log.info("🤖 开始执行统一购票命令: trainNumber={}, username={}", normalizedTrainNumber, username);

        String orderSn = UUID.randomUUID().toString();
        boolean reserved = false;

        try {
            List<Map<String, Object>> existingOrders = jdbcTemplate.queryForList(
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

            Train train = trainService.getOne(new QueryWrapper<Train>().eq("train_number", normalizedTrainNumber));
            if (train == null) {
                audit("BOOK", "DENY", username, null, normalizedTrainNumber, "train_not_found");
                return observation("bookTicket", false, "TRAIN_NOT_FOUND",
                        "很抱歉，车次 " + normalizedTrainNumber + " 不存在，请确认车次号是否正确。",
                        true, false, Map.of("trainNumber", normalizedTrainNumber));
            }

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

            LocalDateTime now = LocalDateTime.now();
            orderInventoryStateService.insertPendingOrder(orderSn, normalizedTrainNumber, username, now);

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
            log.error("🤖 统一购票异常: {}", e.getMessage());
            audit("BOOK", "ERROR", username, orderSn, normalizedTrainNumber, "exception_" + abbreviateReason(e.getClass().getSimpleName()));
            return observation("bookTicket", false, "BOOK_ERROR",
                    "购票操作失败，请稍后重试或联系人工客服。错误信息：" + e.getMessage(),
                    true, true,
                    Map.of("orderSn", orderSn, "trainNumber", normalizedTrainNumber,
                            "reason", abbreviateReason(e.getClass().getSimpleName())));
        }
    }

    @Transactional
    public ToolObservation bookTicketByRoute(String date, String fromStation, String toStation, String username) {
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
                .min(java.util.Comparator.comparing(Train::getStartTime, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .orElse(candidateTrains.get(0));

        ToolObservation bookObservation = bookTicket(selectedTrain.getTrainNumber(), username);
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
        log.info("audit action={} result={} username={} orderSn={} trainNumber={} reason={} traceId={}",
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
        if (!java.util.regex.Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z]{2,30}$").matcher(normalized).matches()) {
            return label + "格式不合法，请使用中文站名或英文站名（2-30字符）。";
        }
        return null;
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
        String normalized = trainNumber.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.regex.Pattern.compile("(?i)^(G|D|C|Z|T|K|Y|L)\\d{1,4}$").matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String abbreviateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        return reason.length() <= 40 ? reason : reason.substring(0, 40);
    }
}
