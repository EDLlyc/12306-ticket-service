package com.ahu.ticket;

import com.ahu.ticket.agent.ZhipuOfficialAgent;
import com.ahu.ticket.agent.plan.ActionExecutionResult;
import com.ahu.ticket.agent.plan.ActionPlanService;
import com.ahu.ticket.agent.plan.ActionPlanStep;
import com.ahu.ticket.agent.plan.ToolExecutionService;
import com.ahu.ticket.agent.plan.ToolObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ActionPlanServiceTest {

    @Mock
    private ZhipuOfficialAgent zhipuOfficialAgent;

    @Mock
    private ToolExecutionService toolExecutionService;

    @InjectMocks
    private ActionPlanService actionPlanService;

    @Test
    public void testExecute_singleToolPlan() {
        when(zhipuOfficialAgent.getToolDescriptors()).thenReturn(List.of(
                new ZhipuOfficialAgent.ToolDescriptor("cancelOrder", "退票", List.of("orderSn", "username"))
        ));
        when(zhipuOfficialAgent.supportsTool("cancelOrder")).thenReturn(true);
        when(zhipuOfficialAgent.generateAgentText(anyString(), anyString())).thenReturn("""
                {
                  "goal":"退掉指定订单",
                  "steps":[
                    {"id":"s1","type":"TOOL","tool":"cancelOrder","args":{"orderSn":"abc-123"}},
                    {"id":"s2","type":"RESPOND","instruction":"告诉用户退票结果"}
                  ]
                }
                """);
        when(toolExecutionService.execute(eq("cancelOrder"), anyMap(), eq("testUser")))
                .thenReturn(ToolObservation.of("cancelOrder", true, "REFUND_SUCCESS", "✅ 退票成功", Map.of(), true, false));

        ActionExecutionResult result = actionPlanService.execute("s1", "testUser", "帮我退票", "safe-question");

        assertEquals("✅ 退票成功", result.finalAnswer());
        assertFalse(result.replanned());
        assertFalse(result.fallback());
        assertTrue(result.planSummary().contains("cancelOrder"));
        ActionPlanStep toolStep = result.plan().getSteps().get(0);
        assertEquals("✅ 退票成功", toolStep.getObservation());
        assertNotNull(toolStep.getObservationDetail());
        assertEquals("TOOL", toolStep.getObservationDetail().getSource());
        assertEquals("REFUND_SUCCESS", toolStep.getObservationDetail().getCode());
    }

    @Test
    public void testExecute_replanWhenPlannerUsesUnknownTool() {
        when(zhipuOfficialAgent.getToolDescriptors()).thenReturn(List.of(
                new ZhipuOfficialAgent.ToolDescriptor("queryMyOrders", "查我的订单", List.of("username"))
        ));
        when(zhipuOfficialAgent.generateAgentText(anyString(), anyString()))
                .thenReturn("""
                        {
                          "goal":"查订单",
                          "steps":[
                            {"id":"s1","type":"TOOL","tool":"imaginaryTool","args":{}},
                            {"id":"s2","type":"RESPOND","instruction":"告诉用户结果"}
                          ]
                        }
                        """)
                .thenReturn("""
                        {
                          "goal":"查订单",
                          "steps":[
                            {"id":"s1","type":"TOOL","tool":"queryMyOrders","args":{"username":"当前用户"}},
                            {"id":"s2","type":"RESPOND","instruction":"告诉用户结果"}
                          ]
                        }
                        """);
        when(zhipuOfficialAgent.supportsTool("imaginaryTool")).thenReturn(false);
        when(zhipuOfficialAgent.supportsTool("queryMyOrders")).thenReturn(true);
        when(toolExecutionService.execute(eq("queryMyOrders"), anyMap(), eq("testUser")))
                .thenReturn(ToolObservation.of("queryMyOrders", true, "ORDERS_FOUND", "您共有 2 条订单记录", Map.of(), true, false));

        ActionExecutionResult result = actionPlanService.execute("s1", "testUser", "查一下我的订单", "safe-question");

        assertEquals("您共有 2 条订单记录", result.finalAnswer());
        assertTrue(result.replanned());
        assertFalse(result.fallback());
        verify(zhipuOfficialAgent, times(2)).generateAgentText(anyString(), anyString());
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(zhipuOfficialAgent, times(2)).generateAgentText(anyString(), userPromptCaptor.capture());
        String replanPrompt = userPromptCaptor.getAllValues().get(1);
        assertTrue(replanPrompt.contains("【已执行 observation】"));
        assertTrue(replanPrompt.contains("PLANNER_UNKNOWN_TOOL"));
        assertTrue(replanPrompt.contains("planner_unknown_tool"));
    }
}
