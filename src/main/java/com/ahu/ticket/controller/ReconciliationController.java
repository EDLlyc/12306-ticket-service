package com.ahu.ticket.controller;

import com.ahu.ticket.common.Result;
import com.ahu.ticket.order.OrderInventoryStateService;
import com.ahu.ticket.order.RefundCompensationService;
import com.ahu.ticket.service.StockBucketService;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/audit/reconcile")
public class ReconciliationController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StockBucketService stockBucketService;

    @Autowired
    private RefundCompensationService refundCompensationService;

    @Autowired
    private OrderInventoryStateService orderInventoryStateService;

    @Autowired
    private TrainInventoryLedgerService trainInventoryLedgerService;

    @GetMapping("/stock")
    public Result<Map<String, Object>> reconcileAllTrains() {
        List<String> trainNumbers = jdbcTemplate.queryForList(
                "SELECT train_number FROM t_train ORDER BY train_number",
                String.class
        );

        List<Map<String, Object>> details = new ArrayList<>();
        int mismatchCount = 0;

        for (String trainNumber : trainNumbers) {
            Map<String, Object> row = reconcileOneTrain(trainNumber);
            details.add(row);
            if (!(Boolean) row.get("consistent")) {
                mismatchCount++;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTrains", trainNumbers.size());
        response.put("mismatchCount", mismatchCount);
        response.put("allConsistent", mismatchCount == 0);
        response.put("details", details);

        return Result.success("库存对账完成", response);
    }

    @GetMapping("/stock/{trainNumber}")
    public Result<Map<String, Object>> reconcileSingleTrain(@PathVariable String trainNumber) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_train WHERE train_number = ?",
                Integer.class,
                trainNumber
        );
        if (exists == null || exists == 0) {
            return Result.notFound("车次不存在: " + trainNumber);
        }
        return Result.success("单车次对账完成", reconcileOneTrain(trainNumber));
    }

    @PostMapping("/refund/replay")
    public Result<Map<String, Object>> replayRefundCompensation(
            @RequestParam(required = false) String trainNumber,
            @RequestParam(defaultValue = "100") int limit) {
        RefundCompensationService.ReplayResult replayResult =
                refundCompensationService.replayPending(trainNumber, limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trainNumber", trainNumber);
        response.put("requestedLimit", replayResult.getRequestedLimit());
        response.put("matchedCount", replayResult.getMatchedCount());
        response.put("compensatedCount", replayResult.getCompensatedCount());
        response.put("skippedCount", replayResult.getSkippedCount());
        response.put("errorCount", replayResult.getErrorCount());
        response.put("details", replayResult.getDetails());
        return Result.success("退票补偿重放完成", response);
    }

    @PostMapping("/stock/rebuild/{trainNumber}")
    public Result<Map<String, Object>> rebuildRedisStock(@PathVariable String trainNumber) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_train WHERE train_number = ?",
                Integer.class,
                trainNumber
        );
        if (exists == null || exists == 0) {
            return Result.notFound("车次不存在: " + trainNumber);
        }

        Map<String, Object> before = reconcileOneTrain(trainNumber);
        int expectedRedisStock = (Integer) before.get("expectedRedisStock");
        boolean impossibleState = Boolean.TRUE.equals(before.get("impossibleState"));
        if (impossibleState) {
            return Result.conflict("当前业务数据自相矛盾，禁止直接重建 Redis 库存。请先修复订单事实状态。");
        }

        stockBucketService.initSegmentedStock(trainNumber, Math.max(0, expectedRedisStock));
        Map<String, Object> after = reconcileOneTrain(trainNumber);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("trainNumber", trainNumber);
        response.put("expectedRedisStock", expectedRedisStock);
        response.put("before", before);
        response.put("after", after);
        return Result.success("Redis 库存重建完成", response);
    }

    private Map<String, Object> reconcileOneTrain(String trainNumber) {
        TrainInventoryLedgerService.InventorySnapshot inventorySnapshot =
                trainInventoryLedgerService.getInventorySnapshot(trainNumber);
        Integer dbStock = inventorySnapshot.getLegacyRemainingStock();
        int createdCount = countOrders(trainNumber, "CREATED");
        int pendingCount = countOrders(trainNumber, "PENDING");
        int processingCount = countOrders(trainNumber, "PROCESSING");
        int successCount = countOrders(trainNumber, "SUCCESS");
        int failedCount = countOrders(trainNumber, "FAILED");
        int cancelledCount = countOrders(trainNumber, "CANCELLED");
        int reservedNotConfirmedCount = orderInventoryStateService.countReservedNotConfirmed(trainNumber);
        int releasePendingCount = orderInventoryStateService.countReleasePending(trainNumber);

        Integer redisStock = stockBucketService.getTotalStock(trainNumber);
        int bucketSum = stockBucketService.sumBucketStock(trainNumber);

        int safeAvailableStock = inventorySnapshot.getAvailableStock() == null ? 0 : inventorySnapshot.getAvailableStock();
        // Runtime semantics after inventory model split:
        // 1. available_stock means remaining sellable stock.
        // 2. locked_stock means pending unpaid reservations already removed from sale.
        // 3. sold_stock means successfully paid orders.
        // 4. inventory_status = RESERVED means Redis has reserved stock but MySQL available/locked ledger has not fully settled yet.
        // 5. inventory_status = RELEASE_PENDING means MySQL has already returned stock, but Redis is still waiting for compensation.
        int expectedRedisStock = safeAvailableStock - reservedNotConfirmedCount - releasePendingCount;
        Integer delta = redisStock == null ? null : redisStock - expectedRedisStock;
        boolean impossibleState = expectedRedisStock < 0;
        boolean stockConsistent = redisStock != null && redisStock == expectedRedisStock;
        boolean bucketConsistent = bucketSum == expectedRedisStock;
        boolean consistent = stockConsistent && bucketConsistent;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trainNumber", trainNumber);
        row.put("dbStock", dbStock);
        row.put("availableStock", inventorySnapshot.getAvailableStock());
        row.put("lockedStock", inventorySnapshot.getLockedStock());
        row.put("soldStock", inventorySnapshot.getSoldStock());
        row.put("detailedInventoryMode", inventorySnapshot.isDetailedMode());
        row.put("redisStock", redisStock);
        row.put("expectedRedisStock", expectedRedisStock);
        row.put("delta", delta);
        row.put("bucketSumStock", bucketSum);
        row.put("bucketCount", stockBucketService.getBucketCount());
        row.put("createdCount", createdCount);
        row.put("pendingCount", pendingCount);
        row.put("processingCount", processingCount);
        row.put("successCount", successCount);
        row.put("failedCount", failedCount);
        row.put("cancelledCount", cancelledCount);
        row.put("reservedNotConfirmedCount", reservedNotConfirmedCount);
        row.put("releasePendingCount", releasePendingCount);
        row.put("activeConsumedOrderCount", inventorySnapshot.isDetailedMode()
                ? safeInt(inventorySnapshot.getLockedStock()) + safeInt(inventorySnapshot.getSoldStock())
                : pendingCount + successCount);
        row.put("impossibleState", impossibleState);
        row.put("stockConsistent", stockConsistent);
        row.put("bucketConsistent", bucketConsistent);
        row.put("consistent", consistent);
        row.put("formula", inventorySnapshot.isDetailedMode()
                ? "expectedRedisStock = availableStock - reservedNotConfirmedCount - releasePendingCount; and bucketSumStock == expectedRedisStock"
                : "expectedRedisStock = dbStock - reservedNotConfirmedCount - releasePendingCount; and bucketSumStock == expectedRedisStock");
        row.put("explanation", inventorySnapshot.isDetailedMode()
                ? "availableStock is remaining sellable MySQL stock; lockedStock is unpaid reserved stock; soldStock is paid stock. RESERVED means Redis has pre-deducted but DB ledger has not fully settled; RELEASE_PENDING means DB has already returned stock but Redis is still waiting for release compensation."
                : "dbStock is remaining MySQL stock. RESERVED inventory means Redis has pre-deducted but DB has not fully settled; RELEASE_PENDING means DB has returned stock but Redis is still waiting for release compensation.");
        row.put("warning", impossibleState
                ? "expectedRedisStock < 0 means the current business data is already self-contradictory, usually due to historical manual data changes or an unfinished compensation path."
                : "");
        row.put("redisKey", "train:stock:" + trainNumber);
        return row;
    }

    private int countOrders(String trainNumber, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE train_number = ? AND status = ?",
                Integer.class,
                trainNumber,
                status
        );
        return count == null ? 0 : count;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
