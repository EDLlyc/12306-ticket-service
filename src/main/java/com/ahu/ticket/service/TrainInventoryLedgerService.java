package com.ahu.ticket.service;

import com.ahu.ticket.entity.Train;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TrainInventoryLedgerService {

    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean detailedInventoryColumnsPresent;

    public TrainInventoryLedgerService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasDetailedInventoryColumns() {
        Boolean cached = detailedInventoryColumnsPresent;
        if (cached != null) {
            return cached;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_train' " +
                        "AND COLUMN_NAME IN ('available_stock', 'locked_stock', 'sold_stock')",
                Integer.class
        );
        boolean present = count != null && count == 3;
        detailedInventoryColumnsPresent = present;
        return present;
    }

    public int getInitialAvailableStock(Train train) {
        if (train == null) {
            return 0;
        }
        if (hasDetailedInventoryColumns() && train.getAvailableStock() != null) {
            return train.getAvailableStock();
        }
        return train.getStock() == null ? 0 : train.getStock();
    }

    public Integer resolveAvailableStock(String trainNumber) {
        return queryInt(
                hasDetailedInventoryColumns()
                        ? "SELECT available_stock FROM t_train WHERE train_number = ?"
                        : "SELECT stock FROM t_train WHERE train_number = ?",
                trainNumber
        );
    }

    public Integer resolveAvailableStock(Train train) {
        if (train == null) {
            return null;
        }
        if (hasDetailedInventoryColumns() && train.getAvailableStock() != null) {
            return train.getAvailableStock();
        }
        return train.getStock();
    }

    public boolean lockAvailableInventory(String trainNumber) {
        if (hasDetailedInventoryColumns()) {
            return update(
                    "UPDATE t_train " +
                            "SET available_stock = available_stock - 1, " +
                            "locked_stock = locked_stock + 1, " +
                            "stock = available_stock - 1 " +
                            "WHERE train_number = ? AND available_stock > 0",
                    trainNumber
            );
        }
        return update(
                "UPDATE t_train SET stock = stock - 1 WHERE train_number = ? AND stock > 0",
                trainNumber
        );
    }

    public boolean confirmLockedInventoryAsSold(String trainNumber) {
        if (!hasDetailedInventoryColumns()) {
            return true;
        }
        return update(
                "UPDATE t_train " +
                        "SET locked_stock = locked_stock - 1, sold_stock = sold_stock + 1 " +
                        "WHERE train_number = ? AND locked_stock > 0",
                trainNumber
        );
    }

    public boolean releaseLockedInventory(String trainNumber) {
        if (hasDetailedInventoryColumns()) {
            return update(
                    "UPDATE t_train " +
                            "SET available_stock = available_stock + 1, " +
                            "locked_stock = locked_stock - 1, " +
                            "stock = available_stock + 1 " +
                            "WHERE train_number = ? AND locked_stock > 0",
                    trainNumber
            );
        }
        return update(
                "UPDATE t_train SET stock = stock + 1 WHERE train_number = ?",
                trainNumber
        );
    }

    public boolean releaseSoldInventory(String trainNumber) {
        if (hasDetailedInventoryColumns()) {
            return update(
                    "UPDATE t_train " +
                            "SET available_stock = available_stock + 1, " +
                            "sold_stock = sold_stock - 1, " +
                            "stock = available_stock + 1 " +
                            "WHERE train_number = ? AND sold_stock > 0",
                    trainNumber
            );
        }
        return update(
                "UPDATE t_train SET stock = stock + 1 WHERE train_number = ?",
                trainNumber
        );
    }

    public InventorySnapshot getInventorySnapshot(String trainNumber) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                hasDetailedInventoryColumns()
                        ? "SELECT stock, available_stock, locked_stock, sold_stock FROM t_train WHERE train_number = ?"
                        : "SELECT stock FROM t_train WHERE train_number = ?",
                trainNumber
        );
        if (rows.isEmpty()) {
            return InventorySnapshot.empty();
        }
        Map<String, Object> row = rows.get(0);
        Integer legacyRemaining = toInt(row.get("stock"));
        if (hasDetailedInventoryColumns()) {
            return new InventorySnapshot(
                    legacyRemaining,
                    toInt(row.get("available_stock")),
                    toInt(row.get("locked_stock")),
                    toInt(row.get("sold_stock")),
                    true
            );
        }
        return new InventorySnapshot(legacyRemaining, legacyRemaining, null, null, false);
    }

    private boolean update(String sql, String trainNumber) {
        return jdbcTemplate.update(sql, trainNumber) > 0;
    }

    private Integer queryInt(String sql, String trainNumber) {
        return jdbcTemplate.query(sql, rs -> rs.next() ? rs.getInt(1) : null, trainNumber);
    }

    private Integer toInt(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw));
    }

    public static class InventorySnapshot {
        private final Integer legacyRemainingStock;
        private final Integer availableStock;
        private final Integer lockedStock;
        private final Integer soldStock;
        private final boolean detailedMode;

        public InventorySnapshot(Integer legacyRemainingStock,
                                 Integer availableStock,
                                 Integer lockedStock,
                                 Integer soldStock,
                                 boolean detailedMode) {
            this.legacyRemainingStock = legacyRemainingStock;
            this.availableStock = availableStock;
            this.lockedStock = lockedStock;
            this.soldStock = soldStock;
            this.detailedMode = detailedMode;
        }

        public static InventorySnapshot empty() {
            return new InventorySnapshot(null, null, null, null, false);
        }

        public Integer getLegacyRemainingStock() {
            return legacyRemainingStock;
        }

        public Integer getAvailableStock() {
            return availableStock;
        }

        public Integer getLockedStock() {
            return lockedStock;
        }

        public Integer getSoldStock() {
            return soldStock;
        }

        public boolean isDetailedMode() {
            return detailedMode;
        }
    }
}
