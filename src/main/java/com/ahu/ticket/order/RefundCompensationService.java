package com.ahu.ticket.order;

import com.ahu.ticket.service.StockBucketService;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RefundCompensationService {

    public static final String REFUND_TOPIC = "ticket-refund-topic";

    private final JdbcTemplate jdbcTemplate;
    private final StockBucketService stockBucketService;
    private final RocketMQTemplate rocketMQTemplate;
    private final OrderInventoryStateService orderInventoryStateService;
    private final TrainInventoryLedgerService trainInventoryLedgerService;

    public RefundCompensationService(JdbcTemplate jdbcTemplate,
                                     StockBucketService stockBucketService,
                                     RocketMQTemplate rocketMQTemplate,
                                     OrderInventoryStateService orderInventoryStateService,
                                     TrainInventoryLedgerService trainInventoryLedgerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockBucketService = stockBucketService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.orderInventoryStateService = orderInventoryStateService;
        this.trainInventoryLedgerService = trainInventoryLedgerService;
    }

    public void publishAfterCommit(String trainNumber, String orderSn) {
        Runnable publishTask = () -> rocketMQTemplate.convertAndSend(REFUND_TOPIC, trainNumber + "," + orderSn);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishTask.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishTask.run();
            }
        });
    }

    public CompensationOutcome process(String trainNumber, String orderSn) {
        Map<String, Object> order = findOrder(orderSn);
        if (order == null) {
            return CompensationOutcome.skipped("ORDER_NOT_FOUND");
        }

        String status = String.valueOf(order.get("status"));
        if (!"CANCELLED".equals(status)) {
            return CompensationOutcome.skipped("ORDER_NOT_CANCELLED");
        }

        if (hasRefundCompensateField()) {
            Object compensatedRaw = order.get("refund_redis_compensated");
            int compensated = toFlag(compensatedRaw);
            if (compensated == 1) {
                return CompensationOutcome.skipped("ALREADY_COMPENSATED");
            }
        }

        boolean ok = stockBucketService.releaseByOrderSn(trainNumber, orderSn);
        if (!ok) {
            ok = reconcileMissingOrderBucketRelease(trainNumber, orderSn);
        }
        if (!ok) {
            throw new IllegalStateException("Redis refund compensation failed");
        }

        markCompensationCompleted(orderSn);
        return CompensationOutcome.compensated();
    }

    public ReplayResult replayPending(String trainNumber, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        boolean hasCompensateField = hasRefundCompensateField();

        StringBuilder sql = new StringBuilder(
                "SELECT order_sn, train_number FROM t_order WHERE status = 'CANCELLED'"
        );
        if (orderInventoryStateService.hasInventoryStatusColumn()) {
            sql.append(" AND inventory_status = '").append(OrderInventoryStateService.RELEASE_PENDING).append("'");
        } else if (hasCompensateField) {
            sql.append(" AND refund_redis_compensated = 0");
        }
        if (trainNumber != null && !trainNumber.isBlank()) {
            sql.append(" AND train_number = ?");
        }
        sql.append(" ORDER BY id ASC LIMIT ").append(safeLimit);

        List<Map<String, Object>> rows = (trainNumber != null && !trainNumber.isBlank())
                ? jdbcTemplate.queryForList(sql.toString(), trainNumber)
                : jdbcTemplate.queryForList(sql.toString());

        ReplayResult result = new ReplayResult();
        result.setRequestedLimit(safeLimit);
        result.setMatchedCount(rows.size());

        for (Map<String, Object> row : rows) {
            String orderSn = String.valueOf(row.get("order_sn"));
            String rowTrainNumber = String.valueOf(row.get("train_number"));
            try {
                CompensationOutcome outcome = process(rowTrainNumber, orderSn);
                result.addDetail(orderSn, rowTrainNumber, outcome.getCode());
            } catch (Exception e) {
                result.addDetail(orderSn, rowTrainNumber, "ERROR_" + e.getClass().getSimpleName());
            }
        }
        return result;
    }

    private Map<String, Object> findOrder(String orderSn) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, refund_redis_compensated FROM t_order WHERE order_sn = ?",
                orderSn
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean reconcileMissingOrderBucketRelease(String trainNumber, String orderSn) {
        if (stockBucketService.hasOrderBucketMapping(orderSn)) {
            return false;
        }

        Integer redisStock = stockBucketService.getTotalStock(trainNumber);
        if (redisStock == null) {
            return false;
        }

        int bucketSum = stockBucketService.sumBucketStock(trainNumber);
        if (bucketSum != redisStock) {
            return false;
        }

        TrainInventoryLedgerService.InventorySnapshot snapshot =
                trainInventoryLedgerService.getInventorySnapshot(trainNumber);
        Integer availableStock = snapshot.getAvailableStock() != null
                ? snapshot.getAvailableStock()
                : snapshot.getLegacyRemainingStock();
        if (availableStock == null) {
            return false;
        }

        int reservedNotConfirmedCount = orderInventoryStateService.countReservedNotConfirmed(trainNumber);
        int releasePendingCount = orderInventoryStateService.countReleasePending(trainNumber);
        int expectedRedisStock = availableStock - reservedNotConfirmedCount - releasePendingCount;

        // If Redis is already ahead of the expected pending-release formula,
        // the stock was likely released earlier and only the order metadata got stuck.
        if (redisStock > expectedRedisStock) {
            return true;
        }

        return stockBucketService.releaseWithoutOrderBucket(trainNumber, orderSn);
    }

    private void markCompensationCompleted(String orderSn) {
        if (hasRefundCompensateField()) {
            jdbcTemplate.update(
                    "UPDATE t_order SET refund_redis_compensated = 1 WHERE order_sn = ? AND refund_redis_compensated = 0",
                    orderSn
            );
        }
        orderInventoryStateService.markInventoryReleased(orderSn);
        stockBucketService.clearOrderBucketMapping(orderSn);
    }

    private boolean hasRefundCompensateField() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' AND COLUMN_NAME = 'refund_redis_compensated'",
                Integer.class
        );
        return count != null && count > 0;
    }

    private int toFlag(Object raw) {
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        String text = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(text) || text.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(text);
    }

    public static class CompensationOutcome {
        private final boolean compensated;
        private final String code;

        private CompensationOutcome(boolean compensated, String code) {
            this.compensated = compensated;
            this.code = code;
        }

        public static CompensationOutcome compensated() {
            return new CompensationOutcome(true, "COMPENSATED");
        }

        public static CompensationOutcome skipped(String code) {
            return new CompensationOutcome(false, code);
        }

        public boolean isCompensated() {
            return compensated;
        }

        public String getCode() {
            return code;
        }
    }

    public static class ReplayResult {
        private int requestedLimit;
        private int matchedCount;
        private int compensatedCount;
        private int skippedCount;
        private int errorCount;
        private final List<Map<String, Object>> details = new java.util.ArrayList<>();

        public void addDetail(String orderSn, String trainNumber, String resultCode) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("orderSn", orderSn);
            detail.put("trainNumber", trainNumber);
            detail.put("result", resultCode);
            details.add(detail);

            if ("COMPENSATED".equals(resultCode)) {
                compensatedCount++;
            } else if (resultCode.startsWith("ERROR_")) {
                errorCount++;
            } else {
                skippedCount++;
            }
        }

        public int getRequestedLimit() {
            return requestedLimit;
        }

        public void setRequestedLimit(int requestedLimit) {
            this.requestedLimit = requestedLimit;
        }

        public int getMatchedCount() {
            return matchedCount;
        }

        public void setMatchedCount(int matchedCount) {
            this.matchedCount = matchedCount;
        }

        public int getCompensatedCount() {
            return compensatedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public int getErrorCount() {
            return errorCount;
        }

        public List<Map<String, Object>> getDetails() {
            return details;
        }
    }
}
