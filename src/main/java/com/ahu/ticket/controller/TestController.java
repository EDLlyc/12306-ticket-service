package com.ahu.ticket.controller;

import com.ahu.ticket.agent.ZhipuOfficialAgent;
import com.ahu.ticket.common.Result;
import com.ahu.ticket.service.impl.TicketTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private TicketTools ticketTools;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ZhipuOfficialAgent zhipuOfficialAgent;

    @GetMapping("/refund")
    public Result<String> testRefund(
            @RequestParam String orderSn,
            @RequestParam String username) {
        log.info("测试退票工具: orderSn={}, username={}", orderSn, username);
        return Result.success(ticketTools.cancelOrder(orderSn, username));
    }

    @GetMapping("/orders/all")
    public List<Map<String, Object>> getAllOrders() {
        log.info("查询所有订单");
        return jdbcTemplate.queryForList("SELECT * FROM t_order");
    }

    @PostMapping("/refund-all-success")
    public Result<String> refundAllSuccessOrders() {
        log.info("批量退票所有SUCCESS状态的订单");
        
        List<Map<String, Object>> successOrders = jdbcTemplate.queryForList(
                "SELECT * FROM t_order WHERE status = 'SUCCESS'");

        if (successOrders.isEmpty()) {
            return Result.success("没有SUCCESS状态的订单需要退票。");
        }
        
        StringBuilder result = new StringBuilder();
        result.append("找到 ").append(successOrders.size()).append(" 个SUCCESS状态的订单：\n\n");
        
        for (Map<String, Object> order : successOrders) {
            String orderSn = String.valueOf(order.get("order_sn"));
            String username = String.valueOf(order.get("username"));
            log.info("退票订单: orderSn={}, username={}", orderSn, username);
            String refundResult = ticketTools.cancelOrder(orderSn, username);
            result.append("订单 ").append(orderSn).append(" (").append(order.get("train_number")).append("): ");
            result.append(refundResult).append("\n");
        }
        
        result.append("\n✅ 批量退票完成！");
        return Result.success(result.toString());
    }

    @PostMapping("/reset-order")
    public Result<String> resetOrder(@RequestParam String orderSn) {
        log.info("重置订单状态为SUCCESS: orderSn={}", orderSn);
        
        int updated = jdbcTemplate.update(
                "UPDATE t_order SET status = 'SUCCESS' WHERE order_sn = ?",
                orderSn);
        
        if (updated > 0) {
            return Result.success("✅ 订单 " + orderSn + " 状态已重置为SUCCESS");
        } else {
            return Result.notFound("❌ 未找到订单 " + orderSn);
        }
    }

    @GetMapping("/zhipu-agent-test")
    public Result<String> testZhipuAgent(
            @RequestParam String question,
            @RequestParam String username) {
        log.info("测试智谱AI官方Agent: question={}, username={}", question, username);
        try {
            String sessionId = "test-session-" + System.currentTimeMillis();
            String result = zhipuOfficialAgent.chat(sessionId, question, username);
            log.info("智谱AI官方Agent返回: {}", result);
            return Result.success(result);
        } catch (Exception e) {
            log.error("智谱AI官方Agent测试失败", e);
            return Result.internalError("❌ 测试失败: " + e.getMessage());
        }
    }
}
