package com.ahu.ticket.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderInventoryStateService {

    public static final String RESERVED = "RESERVED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String RELEASE_PENDING = "RELEASE_PENDING";
    public static final String RELEASED = "RELEASED";

    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean inventoryStatusColumnPresent;

    public OrderInventoryStateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertCreatedOrder(String orderSn, String trainNumber, String username, LocalDateTime now) {
        if (hasInventoryStatusColumn()) {
            jdbcTemplate.update(
                    "INSERT INTO t_order (order_sn, train_number, username, status, inventory_status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, 'CREATED', ?, ?, ?)",
                    orderSn, trainNumber, username, RESERVED, now, now
            );
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO t_order (order_sn, train_number, username, status, created_at, updated_at) VALUES (?, ?, ?, 'CREATED', ?, ?)",
                orderSn, trainNumber, username, now, now
        );
    }

    public void insertPendingOrder(String orderSn, String trainNumber, String username, LocalDateTime now) {
        if (hasInventoryStatusColumn()) {
            jdbcTemplate.update(
                    "INSERT INTO t_order (order_sn, train_number, username, status, inventory_status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, 'PENDING', ?, ?, ?)",
                    orderSn, trainNumber, username, RESERVED, now, now
            );
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO t_order (order_sn, train_number, username, status, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', ?, ?)",
                orderSn, trainNumber, username, now, now
        );
    }

    public void markInventoryConfirmed(String orderSn) {
        updateInventoryState(orderSn, CONFIRMED);
    }

    public void markInventoryReleasePending(String orderSn) {
        updateInventoryState(orderSn, RELEASE_PENDING);
    }

    public void markInventoryReleased(String orderSn) {
        updateInventoryState(orderSn, RELEASED);
    }

    public int countReservedNotConfirmed(String trainNumber) {
        if (hasInventoryStatusColumn()) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_order WHERE train_number = ? AND inventory_status = ?",
                    Integer.class,
                    trainNumber,
                    RESERVED
            );
            return count == null ? 0 : count;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE train_number = ? AND (status = 'CREATED' OR status = 'PROCESSING')",
                Integer.class,
                trainNumber
        );
        return count == null ? 0 : count;
    }

    public int countReleasePending(String trainNumber) {
        if (hasInventoryStatusColumn()) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_order WHERE train_number = ? AND inventory_status = ?",
                    Integer.class,
                    trainNumber,
                    RELEASE_PENDING
            );
            return count == null ? 0 : count;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order WHERE train_number = ? AND status = 'CANCELLED' AND refund_redis_compensated = 0",
                Integer.class,
                trainNumber
        );
        return count == null ? 0 : count;
    }

    public boolean hasInventoryStatusColumn() {
        Boolean cached = inventoryStatusColumnPresent;
        if (cached != null) {
            return cached;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' AND COLUMN_NAME = 'inventory_status'",
                Integer.class
        );
        boolean present = count != null && count > 0;
        inventoryStatusColumnPresent = present;
        return present;
    }

    private void updateInventoryState(String orderSn, String inventoryState) {
        if (!hasInventoryStatusColumn()) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE t_order SET inventory_status = ? WHERE order_sn = ?",
                inventoryState,
                orderSn
        );
    }
}
