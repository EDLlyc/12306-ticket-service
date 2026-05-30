package com.ahu.ticket;

import com.ahu.ticket.order.OrderInventoryStateService;
import com.ahu.ticket.order.RefundCompensationService;
import com.ahu.ticket.service.StockBucketService;
import com.ahu.ticket.service.TrainInventoryLedgerService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefundCompensationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StockBucketService stockBucketService;

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @Mock
    private OrderInventoryStateService orderInventoryStateService;

    @Mock
    private TrainInventoryLedgerService trainInventoryLedgerService;

    private RefundCompensationService refundCompensationService;

    @BeforeEach
    void setUp() {
        refundCompensationService = new RefundCompensationService(
                jdbcTemplate,
                stockBucketService,
                rocketMQTemplate,
                orderInventoryStateService,
                trainInventoryLedgerService
        );
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' AND COLUMN_NAME = 'refund_redis_compensated'"),
                eq(Integer.class)
        )).thenReturn(1);
    }

    @Test
    void process_marksReleasedWhenRedisWasAlreadyReleasedButOrderBucketMappingIsMissing() {
        String orderSn = "o1";
        String trainNumber = "G1001";

        when(jdbcTemplate.queryForList(
                eq("SELECT status, refund_redis_compensated FROM t_order WHERE order_sn = ?"),
                eq(orderSn)
        )).thenReturn(List.of(Map.of("status", "CANCELLED", "refund_redis_compensated", 0)));
        when(stockBucketService.releaseByOrderSn(trainNumber, orderSn)).thenReturn(false);
        when(stockBucketService.hasOrderBucketMapping(orderSn)).thenReturn(false);
        when(stockBucketService.getTotalStock(trainNumber)).thenReturn(12);
        when(stockBucketService.sumBucketStock(trainNumber)).thenReturn(12);
        when(trainInventoryLedgerService.getInventorySnapshot(trainNumber))
                .thenReturn(new TrainInventoryLedgerService.InventorySnapshot(11, 11, 0, 0, true));
        when(orderInventoryStateService.countReservedNotConfirmed(trainNumber)).thenReturn(0);
        when(orderInventoryStateService.countReleasePending(trainNumber)).thenReturn(1);

        RefundCompensationService.CompensationOutcome outcome =
                refundCompensationService.process(trainNumber, orderSn);

        assertEquals("COMPENSATED", outcome.getCode());
        verify(stockBucketService, never()).releaseWithoutOrderBucket(trainNumber, orderSn);
        verify(jdbcTemplate).update(
                eq("UPDATE t_order SET refund_redis_compensated = 1 WHERE order_sn = ? AND refund_redis_compensated = 0"),
                eq(orderSn)
        );
        verify(orderInventoryStateService).markInventoryReleased(orderSn);
        verify(stockBucketService).clearOrderBucketMapping(orderSn);
    }

    @Test
    void process_releasesWithoutOrderBucketWhenRedisStillNeedsCompensation() {
        String orderSn = "o2";
        String trainNumber = "G1002";

        when(jdbcTemplate.queryForList(
                eq("SELECT status, refund_redis_compensated FROM t_order WHERE order_sn = ?"),
                eq(orderSn)
        )).thenReturn(List.of(Map.of("status", "CANCELLED", "refund_redis_compensated", 0)));
        when(stockBucketService.releaseByOrderSn(trainNumber, orderSn)).thenReturn(false);
        when(stockBucketService.hasOrderBucketMapping(orderSn)).thenReturn(false);
        when(stockBucketService.getTotalStock(trainNumber)).thenReturn(7);
        when(stockBucketService.sumBucketStock(trainNumber)).thenReturn(7);
        when(trainInventoryLedgerService.getInventorySnapshot(trainNumber))
                .thenReturn(new TrainInventoryLedgerService.InventorySnapshot(8, 8, 0, 0, true));
        when(orderInventoryStateService.countReservedNotConfirmed(trainNumber)).thenReturn(0);
        when(orderInventoryStateService.countReleasePending(trainNumber)).thenReturn(1);
        when(stockBucketService.releaseWithoutOrderBucket(trainNumber, orderSn)).thenReturn(true);

        RefundCompensationService.CompensationOutcome outcome =
                refundCompensationService.process(trainNumber, orderSn);

        assertEquals("COMPENSATED", outcome.getCode());
        verify(stockBucketService).releaseWithoutOrderBucket(trainNumber, orderSn);
        verify(orderInventoryStateService).markInventoryReleased(orderSn);
    }

    @Test
    void process_throwsWhenCompensationStillCannotBeRecovered() {
        String orderSn = "o3";
        String trainNumber = "G1003";

        when(jdbcTemplate.queryForList(
                eq("SELECT status, refund_redis_compensated FROM t_order WHERE order_sn = ?"),
                eq(orderSn)
        )).thenReturn(List.of(Map.of("status", "CANCELLED", "refund_redis_compensated", 0)));
        when(stockBucketService.releaseByOrderSn(trainNumber, orderSn)).thenReturn(false);
        when(stockBucketService.hasOrderBucketMapping(orderSn)).thenReturn(false);
        when(stockBucketService.getTotalStock(trainNumber)).thenReturn(8);
        when(stockBucketService.sumBucketStock(trainNumber)).thenReturn(7);

        assertThrows(IllegalStateException.class,
                () -> refundCompensationService.process(trainNumber, orderSn));

        verify(orderInventoryStateService, never()).markInventoryReleased(orderSn);
        verify(stockBucketService, never()).clearOrderBucketMapping(orderSn);
    }
}
