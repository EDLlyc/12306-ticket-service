package com.ahu.ticket.order;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderSchemaMigration {

    private static final String[] ORDER_TABLES = {"t_order", "t_order_0", "t_order_1", "t_order_2", "t_order_3"};

    private final JdbcTemplate jdbcTemplate;

    public OrderSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        for (String table : ORDER_TABLES) {
            ensureColumn(table, "created_at",
                    "ALTER TABLE " + table + " ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
            ensureColumn(table, "updated_at",
                    "ALTER TABLE " + table + " ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
            ensureColumn(table, "paid_at",
                    "ALTER TABLE " + table + " ADD COLUMN paid_at DATETIME NULL DEFAULT NULL");
        }
    }

    private void ensureColumn(String tableName, String columnName, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class,
                    tableName,
                    columnName);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute(alterSql);
            log.info("已为 {} 添加列 {}", tableName, columnName);
        } catch (Exception e) {
            log.warn("订单表结构校验失败 table={}, column={}, err={}", tableName, columnName, e.getMessage());
        }
    }
}
