package com.ahu.ticket.service.impl;

import com.ahu.ticket.entity.Train;
import com.ahu.ticket.service.ITrainService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Agent 工具类 (Function Calling)
 *
 * 面试亮点：让大模型从"纯聊天机器人"升级为"能操作数据库的智能体"。
 * 大模型通过分析用户意图，输出 JSON 格式的工具调用请求，
 * Java 代码执行真实数据库查询后，将结果封入 Prompt 交回给 LLM 总结。
 */
@Slf4j
@Component
public class TicketTools {

    @Autowired
    private ITrainService trainService;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /** Redis Lua 原子扣减脚本（与 TicketController 保持一致） */
    private static final String LUA_STOCK_DECREASE = "if (redis.call('exists', KEYS[1]) == 1) then " +
            "    local stock = tonumber(redis.call('get', KEYS[1])); " +
            "    if (stock > 0) then " +
            "        redis.call('decr', KEYS[1]); " +
            "        return stock - 1; " +
            "    end; " +
            "    return -1; " +
            "end; " +
            "return -2;";

    /**
     * 查票工具：Agent 调用此方法查询真实数据库中的车次和余票
     */
    @Tool("当用户询问特定日期从出发地到目的地的两地之间的列车余票或时刻表时调用此工具。请告诉用户最新的余票信息。")
    public String searchTrainTickets(
            @P("出发日期，格式 yyyy-MM-dd 例如 2024-05-01") String date,
            @P("出发城市名称") String fromStation,
            @P("到达城市名称") String toStation) {

        log.info("🤖 Agent 正在调用查票工具: {} 从 {} → {}", date, fromStation, toStation);

        // 真实查询数据库：按出发站和到达站模糊匹配
        List<Train> trains = trainService.list(
                new QueryWrapper<Train>()
                        .like("start_station", fromStation)
                        .like("end_station", toStation));

        if (trains.isEmpty()) {
            log.info("🤖 未找到匹配车次: {} → {}", fromStation, toStation);
            return "很抱歉，" + date + " 从 " + fromStation + " 到 " + toStation
                    + " 暂无匹配的列车信息，建议您更换日期或中转站查询。";
        }

        // 格式化查询结果
        StringBuilder result = new StringBuilder();
        result.append("查询结果（").append(date).append(" ").append(fromStation)
                .append(" → ").append(toStation).append("）：\n");

        for (int i = 0; i < trains.size(); i++) {
            Train t = trains.get(i);
            result.append(String.format("%d. %s次列车，%s发车 → %s到达，余票: %d 张\n",
                    i + 1,
                    t.getTrainNumber(),
                    t.getStartTime() != null ? t.getStartTime().format(TIME_FORMAT) : "未知",
                    t.getEndTime() != null ? t.getEndTime().format(TIME_FORMAT) : "未知",
                    t.getStock() != null ? t.getStock() : 0));
        }

        log.info("🤖 查票工具返回 {} 条结果", trains.size());
        return result.toString();
    }

    // ================================================================
    // 工具 2：订单状态查询 (面试亮点：展示 Agent 多工具决策)
    // ================================================================

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /**
     * 查询订单状态：Agent 根据用户提供的订单号查询 t_order 表
     */
    @Tool("当用户询问某个订单的状态、是否购票成功、订单详情时调用此工具。")
    public String queryOrderStatus(
            @P("用户的订单号，格式为 UUID") String orderSn) {

        log.info("🤖 Agent 正在调用订单查询工具: orderSn={}", orderSn);

        try {
            var results = jdbcTemplate.queryForList(
                    "SELECT order_sn, train_number, username, status FROM t_order WHERE order_sn = ?",
                    orderSn);

            if (results.isEmpty()) {
                return "未找到订单号为 " + orderSn + " 的订单记录，请确认订单号是否正确。";
            }

            var row = results.get(0);
            return String.format("订单查询结果：\n订单号: %s\n车次: %s\n乘客: %s\n状态: %s",
                    row.get("order_sn"), row.get("train_number"),
                    row.get("username"), row.get("status"));

        } catch (Exception e) {
            log.error("🤖 订单查询异常: {}", e.getMessage());
            return "订单查询失败，请稍后重试。";
        }
    }

    // ================================================================
    // 工具 3：取消订单 (面试亮点：展示 Agent 写操作 + 库存回补)
    // ================================================================

    /**
     * 取消订单：更新订单状态 + 回补 MySQL/Redis 双端库存
     *
     * 面试话术：Agent 不仅能查，还能写。大模型自动判断用户意图后
     * 选择调用此工具，实现"对话即操作"的智能体能力。
     *
     * 面试关键设计点：
     * 1. @Transactional 保证 MySQL 侧两步操作（改订单状态 + 回补库存）的原子性
     * 2. 执行顺序：先 MySQL（强一致事务保障），再 Redis（允许最终一致）
     * 3. 即使 Redis 回补失败，MySQL 数据是正确的，下次预热可自动恢复
     */
    @Transactional
    @Tool("当用户要求退票、取消订单、撤单或不想要票时调用。支持直接传订单号，也支持只传用户名来自动退最后一张票。")
    public String cancelOrder(
            @P("要退票的订单号 (选填，若用户提供了则必传)") String orderSn,
            @P("当前登录用户名 (必填，用于在未提供单号时自动查找最后一张票)") String username) {

        log.info("🤖 Agent 正在调用智能退票工具: orderSn={}, username={}", orderSn, username);

        try {
            String finalOrderSn = orderSn;

            // 1. 如果没传单号，或者传的是空/null，则根据用户名查最后一张有效订单
            if (finalOrderSn == null || finalOrderSn.isBlank() || "null".equalsIgnoreCase(finalOrderSn)) {
                log.info("🤖 订单号为空，尝试为用户 {} 查找最近一张可退订单...", username);
                var lastOrders = jdbcTemplate.queryForList(
                        "SELECT order_sn FROM t_order WHERE username = ? AND (status = 'SUCCESS' OR status = 'PENDING') ORDER BY id DESC LIMIT 1",
                        username);

                if (lastOrders.isEmpty()) {
                    return "抱歉，未找到您（" + username + "）有任何可退的订单（需为已支付或排队中状态）。";
                }
                finalOrderSn = String.valueOf(lastOrders.get(0).get("order_sn"));
                log.info("🤖 自动匹配到最近订单: {}", finalOrderSn);
            }

            // 2. 执行退票逻辑
            var results = jdbcTemplate.queryForList(
                    "SELECT train_number, status FROM t_order WHERE order_sn = ?", finalOrderSn);

            if (results.isEmpty()) {
                return "未找到订单号为 " + finalOrderSn + " 的记录，请核对。";
            }

            String status = String.valueOf(results.get(0).get("status"));
            if ("CANCELLED".equals(status)) {
                return "订单 " + finalOrderSn + " 已经是取消状态，无需重复操作。";
            }

            String trainNumber = String.valueOf(results.get(0).get("train_number"));

            // 3. 更新订单状态
            jdbcTemplate.update(
                    "UPDATE t_order SET status = 'CANCELLED' WHERE order_sn = ?",
                    finalOrderSn);

            // 4. 回补 MySQL + Redis 库存
            trainService.update(new UpdateWrapper<Train>()
                    .setSql("stock = stock + 1")
                    .eq("train_number", trainNumber));
            redisTemplate.opsForValue().increment("train:stock:" + trainNumber);

            log.info("🤖 智能退票成功: orderSn={}, trainNumber={}", finalOrderSn, trainNumber);
            return "✅ 退票成功！已为您取消订单 " + finalOrderSn + " (车次 " + trainNumber + ")，票款将原路返回。";

        } catch (Exception e) {
            log.error("🤖 智能退票异常: {}", e.getMessage());
            return "❌ 退票失败，系统异常，请稍后重试。";
        }
    }

    // ================================================================
    // 工具 4：购票工具 (面试亮点：Agent 能真正买票！)
    // ================================================================

    /**
     * 购票工具：Agent 根据用户的自然语言购票请求，执行真实扣减库存 + 订单落库
     *
     * 面试话术：Agent 不只是查询助手，还能执行写操作。
     * 大模型解析用户意图后自动提取车次号和用户名，调用此工具完成购票闭环。
     */
    @Transactional
    @Tool("当用户要求购买车票、买票、订票、预订某个车次时调用此工具。需要车次号和用户名。")
    public String bookTicket(
            @P("要购买的车次号，例如 G7236") String trainNumber,
            @P("当前登录的用户名") String username) {

        log.info("🤖 Agent 正在调用购票工具: trainNumber={}, username={}", trainNumber, username);

        try {
            // 1. 验证车次是否存在
            Train train = trainService.getOne(
                    new QueryWrapper<Train>().eq("train_number", trainNumber));
            if (train == null) {
                return "很抱歉，车次 " + trainNumber + " 不存在，请确认车次号是否正确。";
            }

            // 2. Redis Lua 原子扣减库存
            String stockKey = "train:stock:" + trainNumber;
            org.springframework.data.redis.core.script.DefaultRedisScript<Long> script = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    LUA_STOCK_DECREASE, Long.class);
            Long result = redisTemplate.execute(script, java.util.Collections.singletonList(stockKey));

            if (result == null || result < 0) {
                if (result != null && result == -2) {
                    return "车次 " + trainNumber + " 尚未完成库存预热，请先在购票大厅初始化库存后再试。";
                }
                return "很抱歉，车次 " + trainNumber + " 的余票已售完，建议您选择其他车次。";
            }

            // 3. 生成唯一订单号并落库
            String orderSn = java.util.UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO t_order (order_sn, train_number, username, status) VALUES (?, ?, ?, 'PENDING')",
                    orderSn, trainNumber, username);

            // 4. 同步扣减 MySQL 库存
            trainService.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Train>()
                    .setSql("stock = stock - 1")
                    .eq("train_number", trainNumber)
                    .gt("stock", 0));

            log.info("🤖 购票成功: orderSn={}, trainNumber={}, username={}", orderSn, trainNumber, username);
            return String.format("🎉 购票成功！\n订单号: %s\n车次: %s（%s → %s）\n乘客: %s\n状态: 已预订\n\n请妥善保管您的订单号。",
                    orderSn, trainNumber, train.getStartStation(), train.getEndStation(), username);

        } catch (org.springframework.dao.DuplicateKeyException e) {
            return "检测到重复订单，请勿重复购票。";
        } catch (Exception e) {
            log.error("🤖 购票异常: {}", e.getMessage());
            return "购票操作失败，请稍后重试或联系人工客服。错误信息：" + e.getMessage();
        }
    }

    // ================================================================
    // 工具 5：查询我的所有订单 (面试亮点：Agent 个性化服务)
    // ================================================================

    /**
     * 查询用户的所有订单：Agent 根据用户名查询该用户的全部订单记录
     */
    @Tool("当用户询问自己买了哪些票、我的订单列表、我的购票记录时调用此工具。")
    public String queryMyOrders(
            @P("当前登录的用户名") String username) {

        log.info("🤖 Agent 正在调用用户订单查询工具: username={}", username);

        try {
            var results = jdbcTemplate.queryForList(
                    "SELECT order_sn, train_number, username, status FROM t_order WHERE username = ? ORDER BY order_sn DESC",
                    username);

            if (results.isEmpty()) {
                return "您（" + username + "）暂无任何订单记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("您（").append(username).append("）共有 ").append(results.size()).append(" 条订单记录：\n\n");

            for (int i = 0; i < results.size(); i++) {
                var row = results.get(i);
                sb.append(String.format("%d. 订单号: %s | 车次: %s | 状态: %s\n",
                        i + 1, row.get("order_sn"), row.get("train_number"), row.get("status")));
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("🤖 用户订单查询异常: {}", e.getMessage());
            return "订单查询失败，请稍后重试。";
        }
    }
}
