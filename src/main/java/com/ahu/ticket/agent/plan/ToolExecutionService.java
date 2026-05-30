package com.ahu.ticket.agent.plan;

import com.ahu.ticket.service.impl.TicketTools;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ToolExecutionService {

    private final TicketTools ticketTools;

    public ToolExecutionService(TicketTools ticketTools) {
        this.ticketTools = ticketTools;
    }

    public ToolObservation execute(String functionName, Map<String, Object> arguments, String username) {
        Map<String, Object> args = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        return switch (functionName) {
            case "cancelOrder" -> ticketTools.observeCancelOrder(stringArg(args, "orderSn"), username);
            case "cancelOrderByTrainNumber" ->
                    ticketTools.observeCancelOrderByTrainNumber(stringArg(args, "trainNumber"), username);
            case "cancelAllOrdersByTrainNumber" ->
                    ticketTools.observeCancelAllOrdersByTrainNumber(stringArg(args, "trainNumber"), username);
            case "cancelAllOrders" -> ticketTools.observeCancelAllOrders(username);
            case "searchTrainTickets" -> ticketTools.observeSearchTrainTickets(
                    stringArg(args, "date"),
                    stringArg(args, "fromStation"),
                    stringArg(args, "toStation")
            );
            case "queryOrderStatus" -> ticketTools.observeQueryOrderStatus(stringArg(args, "orderSn"), username);
            case "bookTicket" -> ticketTools.observeBookTicket(stringArg(args, "trainNumber"), username);
            case "bookTicketByRoute" -> ticketTools.observeBookTicketByRoute(
                    stringArg(args, "date"),
                    stringArg(args, "fromStation"),
                    stringArg(args, "toStation"),
                    username
            );
            case "queryMyOrders" -> ticketTools.observeQueryMyOrders(username);
            default -> ToolObservation.of(
                    functionName,
                    false,
                    "UNKNOWN_TOOL",
                    "❌ 未知工具: " + functionName,
                    Map.of("tool", functionName),
                    true,
                    false
            );
        };
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
