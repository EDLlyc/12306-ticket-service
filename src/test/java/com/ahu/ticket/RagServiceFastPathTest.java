package com.ahu.ticket;

import com.ahu.ticket.service.impl.RagServiceImpl;
import com.ahu.ticket.service.impl.TicketTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
public class RagServiceFastPathTest {

    @Mock
    private TicketTools ticketTools;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RagServiceImpl ragService;
    private Method fastPathMethod;
    private static final String SESSION_ID = "s1";
    private static final String USERNAME = "昱辰";

    @BeforeEach
    public void setup() throws Exception {
        ragService = new RagServiceImpl();

        Field ticketToolsField = RagServiceImpl.class.getDeclaredField("ticketTools");
        ticketToolsField.setAccessible(true);
        ticketToolsField.set(ragService, ticketTools);

        Field redisTemplateField = RagServiceImpl.class.getDeclaredField("redisTemplate");
        redisTemplateField.setAccessible(true);
        redisTemplateField.set(ragService, redisTemplate);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);

        fastPathMethod = RagServiceImpl.class.getDeclaredMethod("tryHandleLocalAction", String.class, String.class, String.class);
        fastPathMethod.setAccessible(true);
    }

    @Test
    public void testFastPath_cancelOrder() throws Exception {
        when(ticketTools.cancelOrder(eq("f0d977de-fda3-4024-b953-cf4f2b2512fb"), eq("昱辰")))
                .thenReturn("✅ 退票成功");

        String result = (String) fastPathMethod.invoke(ragService,
                SESSION_ID, "帮我退掉订单 f0d977de-fda3-4024-b953-cf4f2b2512fb", USERNAME);

        assertEquals("✅ 退票成功", result);
    }

    @Test
    public void testFastPath_cancelOrderByTrainNumber() throws Exception {
        when(ticketTools.cancelOrderByTrainNumber(eq("G1001"), eq("昱辰")))
                .thenReturn("✅ 按车次退票成功");

        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "G1001 退掉这张车票", USERNAME);

        assertEquals("✅ 按车次退票成功", result);
    }

    @Test
    public void testFastPath_batchRefundAfterAmbiguousTrainRefund() throws Exception {
        doReturn("G1001").when(valueOperations).get("rag:pending_refund_train:s1:昱辰");
        when(ticketTools.cancelOrderByTrainNumber(eq("G1001"), eq("昱辰")))
                .thenReturn("您在车次 G1001 下存在多张可退订单。为避免误退，请提供订单号后再退票。");
        when(ticketTools.cancelAllOrdersByTrainNumber(eq("G1001"), eq("昱辰")))
                .thenReturn("✅ 已为您批量退票成功 2 笔（车次 G1001），库存回补处理中。");

        String first = (String) fastPathMethod.invoke(ragService, SESSION_ID, "G1001退掉这张。车票。", USERNAME);
        assertEquals("您在车次 G1001 下存在多张可退订单。为避免误退，请提供订单号后再退票。", first);

        String second = (String) fastPathMethod.invoke(ragService, SESSION_ID, "都退掉", USERNAME);
        assertEquals("✅ 已为您批量退票成功 2 笔（车次 G1001），库存回补处理中。", second);
        verify(ticketTools).cancelAllOrdersByTrainNumber(eq("G1001"), eq("昱辰"));
    }

    @Test
    public void testFastPath_batchRefundWithoutPendingTrain() throws Exception {
        when(ticketTools.cancelAllOrders(eq("昱辰")))
                .thenReturn("✅ 已为您批量退票成功 3 笔，库存回补处理中。");

        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "都推掉。", USERNAME);
        assertEquals("✅ 已为您批量退票成功 3 笔，库存回补处理中。", result);
    }

    @Test
    public void testFastPath_cancelOrderByTrainNumber_shortCommand() throws Exception {
        when(ticketTools.cancelOrderByTrainNumber(eq("G7501"), eq("昱辰")))
                .thenReturn("✅ G7501 退票成功");

        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "G7501退。", USERNAME);

        assertEquals("✅ G7501 退票成功", result);
    }

    @Test
    public void testFastPath_bookTicket() throws Exception {
        when(ticketTools.bookTicket(eq("G1001"), eq("昱辰")))
                .thenReturn("🎉 购票成功");

        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "帮我买 G1001", USERNAME);

        assertEquals("🎉 购票成功", result);
    }

    @Test
    public void testFastPath_queryMyOrders() throws Exception {
        when(ticketTools.queryMyOrders(eq("昱辰")))
                .thenReturn("您共有 1 条订单记录");

        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "帮我查一下我的订单", USERNAME);

        assertEquals("您共有 1 条订单记录", result);
    }

    @Test
    public void testFastPath_queryOrderStatus() throws Exception {
        when(ticketTools.queryOrderStatus(eq("f0d977de-fda3-4024-b953-cf4f2b2512fb"), eq("昱辰")))
                .thenReturn("订单状态: SUCCESS");

        String result = (String) fastPathMethod.invoke(ragService,
                SESSION_ID, "查一下订单 f0d977de-fda3-4024-b953-cf4f2b2512fb 的状态", USERNAME);

        assertEquals("订单状态: SUCCESS", result);
    }

    @Test
    public void testFastPath_ignoreRagQuestion() throws Exception {
        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "儿童票多高免票？", USERNAME);

        assertNull(result);
    }

    @Test
    public void testFastPath_routeBooking_fillDateInNextTurn() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doReturn(null).when(valueOperations).get("rag:pending_route_booking:s1:昱辰");

        String firstTurn = (String) fastPathMethod.invoke(ragService, SESSION_ID, "南京南 → 杭州东，请你预定。", USERNAME);
        assertEquals("您好！我可以帮您预定从南京南到杭州东的车票。不过您没有提供出发日期，请告诉我您希望哪天出发？请提供具体日期，格式如：2026-04-21", firstTurn);

        doReturn("{\"date\":null,\"fromStation\":\"南京南\",\"toStation\":\"杭州东\"}")
                .when(valueOperations).get("rag:pending_route_booking:s1:昱辰");
        when(ticketTools.bookTicketByRoute(eq("2026-03-25"), eq("南京南"), eq("杭州东"), eq("昱辰")))
                .thenReturn("🎉 购票成功！");

        String secondTurn = (String) fastPathMethod.invoke(ragService, SESSION_ID, "2026-03-25", USERNAME);
        assertEquals("🎉 购票成功！", secondTurn);
        verify(ticketTools).bookTicketByRoute(eq("2026-03-25"), eq("南京南"), eq("杭州东"), eq("昱辰"));
    }

    @Test
    public void testFastPath_routeBooking_dateThenRoute() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doReturn("{\"date\":\"2026-03-25\",\"fromStation\":null,\"toStation\":null}")
                .when(valueOperations).get("rag:pending_route_booking:s1:昱辰");
        when(ticketTools.bookTicketByRoute(eq("2026-03-25"), eq("南京南"), eq("杭州东"), eq("昱辰")))
                .thenReturn("🎉 购票成功！");

        String result = (String) fastPathMethod.invoke(ragService, SESSION_ID, "从南京南到杭州东", USERNAME);
        assertEquals("🎉 购票成功！", result);
        verify(ticketTools).bookTicketByRoute(eq("2026-03-25"), eq("南京南"), eq("杭州东"), eq("昱辰"));
    }
}
