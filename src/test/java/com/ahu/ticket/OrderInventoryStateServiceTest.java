package com.ahu.ticket;

import com.ahu.ticket.order.OrderInventoryStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderInventoryStateServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private OrderInventoryStateService orderInventoryStateService;

    @BeforeEach
    void setUp() {
        orderInventoryStateService = new OrderInventoryStateService(jdbcTemplate);
    }

    @Test
    void insertCreatedOrder_usesInventoryStatusColumnWhenAvailable() {
        ReflectionTestUtils.setField(orderInventoryStateService, "inventoryStatusColumnPresent", true);
        LocalDateTime now = LocalDateTime.of(2026, 4, 30, 14, 0);

        orderInventoryStateService.insertCreatedOrder("o1", "G1001", "alice", now);

        verify(jdbcTemplate).update(
                eq("INSERT INTO t_order (order_sn, train_number, username, status, inventory_status, created_at, updated_at) VALUES (?, ?, ?, 'CREATED', ?, ?, ?)"),
                eq("o1"), eq("G1001"), eq("alice"), eq(OrderInventoryStateService.RESERVED), eq(now), eq(now)
        );
    }

    @Test
    void insertPendingOrder_fallsBackToLegacyInsertWhenColumnMissing() {
        ReflectionTestUtils.setField(orderInventoryStateService, "inventoryStatusColumnPresent", false);
        LocalDateTime now = LocalDateTime.of(2026, 4, 30, 14, 5);

        orderInventoryStateService.insertPendingOrder("o2", "G1002", "bob", now);

        verify(jdbcTemplate).update(
                eq("INSERT INTO t_order (order_sn, train_number, username, status, created_at, updated_at) VALUES (?, ?, ?, 'PENDING', ?, ?)"),
                eq("o2"), eq("G1002"), eq("bob"), eq(now), eq(now)
        );
    }

    @Test
    void countReservedNotConfirmed_usesExplicitInventoryStateWhenColumnAvailable() {
        ReflectionTestUtils.setField(orderInventoryStateService, "inventoryStatusColumnPresent", true);
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM t_order WHERE train_number = ? AND inventory_status = ?"),
                eq(Integer.class),
                eq("G1001"),
                eq(OrderInventoryStateService.RESERVED)
        )).thenReturn(3);

        int count = orderInventoryStateService.countReservedNotConfirmed("G1001");

        assertEquals(3, count);
    }

    @Test
    void countReleasePending_fallsBackToLegacyRefundFlagWhenColumnMissing() {
        ReflectionTestUtils.setField(orderInventoryStateService, "inventoryStatusColumnPresent", false);
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM t_order WHERE train_number = ? AND status = 'CANCELLED' AND refund_redis_compensated = 0"),
                eq(Integer.class),
                eq("G1001")
        )).thenReturn(2);

        int count = orderInventoryStateService.countReleasePending("G1001");

        assertEquals(2, count);
    }
}
