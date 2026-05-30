package com.ahu.ticket.controller;

import com.ahu.ticket.auth.LoginTokenService;
import com.ahu.ticket.common.Result;
import com.ahu.ticket.order.OrderPaymentService;
import com.ahu.ticket.service.impl.TicketTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/user")
public class UserController {
    private static final Pattern ORDER_SN_PATTERN = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Autowired
    private LoginTokenService loginTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketTools ticketTools;
    @Autowired
    private OrderPaymentService orderPaymentService;

    @PostMapping("/login")
    public Result<String> login(@RequestParam String username) {
        return Result.success(loginTokenService.issueToken(username));
    }

    /**
     * 查询登录状态：通过 token 获取用户名
     */
    @GetMapping("/check")
    public Result<Map<String, Object>> checkLogin(@RequestParam String token) {
        String username = loginTokenService.resolveUsername(token);
        if (username != null) {
            return Result.success(Map.of("loggedIn", true, "username", username));
        }
        return Result.success(Map.of("loggedIn", false));
    }

    /**
     * 查询指定用户的所有订单（供前端订单面板使用）
     */
    @GetMapping("/orders")
    public Result<List<Map<String, Object>>> queryOrders(
            @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        return Result.success(jdbcTemplate.queryForList(
                "SELECT order_sn, train_number, username, status, created_at, paid_at FROM t_order " +
                        "WHERE username = ? AND status <> 'CANCELLED' ORDER BY order_sn DESC",
                username));
    }

    @PostMapping("/pay")
    public Result<String> payOrder(
            @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username,
            @RequestParam String orderSn) {
        if (orderSn == null || orderSn.isBlank() || !ORDER_SN_PATTERN.matcher(orderSn.trim()).matches()) {
            return Result.badRequest("订单号格式不合法，请提供标准 UUID（例如 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）。");
        }
        return Result.success(orderPaymentService.payOrder(orderSn.trim(), username));
    }

    @PostMapping("/refund")
    public Result<String> refundOrder(
            @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username,
            @RequestParam String orderSn) {
        if (orderSn == null || orderSn.isBlank() || !ORDER_SN_PATTERN.matcher(orderSn.trim()).matches()) {
            return Result.badRequest("订单号格式不合法，请提供标准 UUID（例如 xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）。");
        }
        return Result.success(ticketTools.cancelOrder(orderSn, username));
    }
}
