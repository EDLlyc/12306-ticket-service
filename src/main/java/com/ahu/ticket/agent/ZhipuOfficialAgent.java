package com.ahu.ticket.agent;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.ahu.ticket.service.impl.TicketTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Component
public class ZhipuOfficialAgent {

    private static final String MODEL_NAME = "glm-4.7-flash";
    private static final int MAX_TOKENS = 8192;

    @Value("${ai.zhipu.api-key}")
    private String apiKey;

    private ZhipuAiClient client;
    private ObjectMapper objectMapper;

    private final TicketTools ticketTools;

    public ZhipuOfficialAgent(TicketTools ticketTools) {
        this.ticketTools = ticketTools;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        log.info("【智谱AI官方Agent】初始化中...");
        try {
            if (apiKey == null || apiKey.isBlank()) {
                log.error("【智谱AI官方Agent】初始化失败：未配置 ai.zhipu.api-key");
                return;
            }
            client = ZhipuAiClient.builder()
                .ofZHIPU()
                .apiKey(apiKey)
                .build();
            log.info("【智谱AI官方Agent】初始化成功！");
        } catch (Exception e) {
            log.error("【智谱AI官方Agent】初始化失败", e);
        }
    }

    public String chat(String sessionId, String question, String username) {
        log.info("【智谱AI官方Agent】收到请求: sessionId={}, question={}", sessionId, question);

        try {
            if (client == null) {
                log.error("【智谱AI官方Agent】客户端未初始化成功");
                return fallbackOrFailure(question, username, "客户端未初始化");
            }

            List<ChatMessage> messages = new ArrayList<>();
            
            String systemPrompt = "你是12306高铁智能客服Agent，可以帮助用户买票、退票、查票、查订单。" +
                "当用户需要使用工具时，必须使用结构化 function call（toolCalls），禁止输出任何 <tool_call> 标签文本。";

            messages.add(ChatMessage.builder()
                .role("system")
                .content(systemPrompt)
                .build());

            String userMessage = "【当前登录用户】: " + username + "\n" +
                "以下是用户的实际问题：\n" +
                question;

            messages.add(ChatMessage.builder()
                .role("user")
                .content(userMessage)
                .build());

            ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(MODEL_NAME)
                .messages(messages)
                .temperature(1.0f)
                .maxTokens(MAX_TOKENS)
                .tools(buildTools())
                .toolChoice("auto")
                .build();

            ChatCompletionResponse response = client.chat().createChatCompletion(request);
            logResponse("first_call", response);

            if (response.isSuccess()) {
                ChatMessage assistantMessage = response.getData().getChoices().get(0).getMessage();
                log.info("【智谱AI官方Agent】模型响应: {}", assistantMessage);

                List<ToolInvocation> toolInvocations = extractToolInvocations(assistantMessage);
                if (!toolInvocations.isEmpty()) {
                    log.info("【智谱AI官方Agent】检测到工具调用！");
                    
                    messages.add(assistantMessage);
                    for (ToolInvocation toolCall : toolInvocations) {
                        String functionName = toolCall.functionName();
                        String arguments = toolCall.argumentsJson();
                        
                        log.info("【智谱AI官方Agent】调用工具: {}, 参数: {}", functionName, arguments);

                        String toolResult = executeTool(functionName, arguments, username);
                        log.info("【智谱AI官方Agent】工具执行结果: {}", toolResult);

                        messages.add(ChatMessage.builder()
                            .role("tool")
                            .toolCallId(toolCall.toolCallId())
                            .content(toolResult)
                            .build());
                    }

                    ChatCompletionCreateParams secondRequest = ChatCompletionCreateParams.builder()
                        .model(MODEL_NAME)
                        .messages(messages)
                        .temperature(1.0f)
                        .maxTokens(MAX_TOKENS)
                        .build();

                    ChatCompletionResponse secondResponse = client.chat().createChatCompletion(secondRequest);
                    logResponse("second_call", secondResponse);

                    if (secondResponse.isSuccess()) {
                        ChatMessage secondAssistantMessage = secondResponse.getData().getChoices().get(0).getMessage();
                        List<ToolInvocation> secondToolInvocations = extractToolInvocations(secondAssistantMessage);
                        if (!secondToolInvocations.isEmpty()) {
                            log.info("【智谱AI官方Agent】second_call 返回结构化工具调用，继续执行");
                            return executeToolInvocations(secondToolInvocations, username);
                        }
                        Object contentObj = secondAssistantMessage.getContent();
                        String finalAnswer = contentObj != null ? contentObj.toString() : "抱歉，无法处理您的请求。";
                        log.info("【智谱AI官方Agent】最终回答: {}", finalAnswer);
                        return finalAnswer;
                    } else {
                        log.error("【智谱AI官方Agent】第二次API调用失败: {}", secondResponse.getMsg());
                        return fallbackOrFailure(question, username, secondResponse.getMsg());
                    }
                } else {
                    Object contentObj = assistantMessage.getContent();
                    String content = contentObj != null ? contentObj.toString() : null;
                    log.warn("【智谱AI官方Agent】未识别到结构化工具调用，content={}", content);
                    log.info("【智谱AI官方Agent】直接回答: {}", content);
                    return content != null ? content : "抱歉，我无法处理您的请求。";
                }
            } else {
                log.error("【智谱AI官方Agent】API调用失败: {}", response.getMsg());
                return fallbackOrFailure(question, username, response.getMsg());
            }

        } catch (Exception e) {
            log.error("【智谱AI官方Agent】处理异常", e);
            return fallbackOrFailure(question, username, e.getMessage());
        }
    }

    private List<ChatTool> buildTools() {
        List<ChatTool> tools = new ArrayList<>();

        // 1. cancelOrder Tool
        Map<String, ChatFunctionParameterProperty> cancelOrderProperties = new HashMap<>();
        cancelOrderProperties.put("orderSn", ChatFunctionParameterProperty.builder()
            .type("string").description("订单号，选填，若用户提供了则必传").build());
        cancelOrderProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录用户名，必填").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("cancelOrder")
                .description("当用户要求退票、取消订单、撤单或不想要票时调用。支持直接传订单号，也支持只传用户名来自动退最后一张票。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(cancelOrderProperties).build())
                .build())
            .build());

        // 2. searchTrainTickets Tool
        Map<String, ChatFunctionParameterProperty> searchProperties = new HashMap<>();
        searchProperties.put("date", ChatFunctionParameterProperty.builder()
            .type("string").description("出发日期，格式 yyyy-MM-dd 例如 2024-05-01").build());
        searchProperties.put("fromStation", ChatFunctionParameterProperty.builder()
            .type("string").description("出发城市名称").build());
        searchProperties.put("toStation", ChatFunctionParameterProperty.builder()
            .type("string").description("到达城市名称").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("searchTrainTickets")
                .description("当用户询问特定日期从出发地到目的地的两地之间的列车余票或时刻表时调用此工具。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(searchProperties).build())
                .build())
            .build());

        // 3. queryOrderStatus Tool
        Map<String, ChatFunctionParameterProperty> queryOrderProperties = new HashMap<>();
        queryOrderProperties.put("orderSn", ChatFunctionParameterProperty.builder()
            .type("string").description("用户的订单号，格式为 UUID").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("queryOrderStatus")
                .description("当用户询问某个订单的状态、是否购票成功、订单详情时调用此工具。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(queryOrderProperties).build())
                .build())
            .build());

        // 4. bookTicket Tool
        Map<String, ChatFunctionParameterProperty> bookProperties = new HashMap<>();
        bookProperties.put("trainNumber", ChatFunctionParameterProperty.builder()
            .type("string").description("要购买的车次号，例如 G7236").build());
        bookProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录的用户名").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("bookTicket")
                .description("当用户要求购买车票、买票、订票、预订某个车次时调用此工具。需要车次号和用户名。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(bookProperties).build())
                .build())
            .build());

        // 5. queryMyOrders Tool
        Map<String, ChatFunctionParameterProperty> myOrdersProperties = new HashMap<>();
        myOrdersProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录的用户名").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("queryMyOrders")
                .description("当用户询问自己买了哪些票、我的订单列表、我的购票记录时调用此工具。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(myOrdersProperties).build())
                .build())
            .build());

        return tools;
    }

    private String executeTool(String functionName, String arguments, String username) {
        try {
            Map<String, Object> args = parseArguments(arguments);
            
            if ("cancelOrder".equals(functionName)) {
                String orderSn = args.containsKey("orderSn") ? String.valueOf(args.get("orderSn")) : null;
                String toolUsername = args.containsKey("username") ? String.valueOf(args.get("username")) : username;
                log.info("【智谱AI官方Agent】执行退票: orderSn={}, username={}", orderSn, toolUsername);
                return ticketTools.cancelOrder(orderSn, toolUsername != null ? toolUsername : username);
            } else if ("searchTrainTickets".equals(functionName)) {
                String date = String.valueOf(args.get("date"));
                String fromStation = String.valueOf(args.get("fromStation"));
                String toStation = String.valueOf(args.get("toStation"));
                return ticketTools.searchTrainTickets(date, fromStation, toStation);
            } else if ("queryOrderStatus".equals(functionName)) {
                String orderSn = String.valueOf(args.get("orderSn"));
                return ticketTools.queryOrderStatus(orderSn);
            } else if ("bookTicket".equals(functionName)) {
                String trainNumber = String.valueOf(args.get("trainNumber"));
                String toolUsername = args.containsKey("username") ? String.valueOf(args.get("username")) : username;
                return ticketTools.bookTicket(trainNumber, toolUsername != null ? toolUsername : username);
            } else if ("queryMyOrders".equals(functionName)) {
                String toolUsername = args.containsKey("username") ? String.valueOf(args.get("username")) : username;
                return ticketTools.queryMyOrders(toolUsername != null ? toolUsername : username);
            }
            
            return "❌ 未知工具: " + functionName;
        } catch (Exception e) {
            log.error("【智谱AI官方Agent】工具执行失败", e);
            return "❌ 工具执行失败: " + e.getMessage();
        }
    }

    private Map<String, Object> parseArguments(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        String normalizedArguments = arguments.trim();
        if (normalizedArguments.startsWith("\"") && normalizedArguments.endsWith("\"")) {
            normalizedArguments = objectMapper.readValue(normalizedArguments, String.class);
        }
        return objectMapper.readValue(normalizedArguments, new TypeReference<Map<String, Object>>() {});
    }

    private String executeToolInvocations(List<ToolInvocation> toolInvocations, String username) {
        List<String> results = new ArrayList<>();
        for (ToolInvocation toolInvocation : toolInvocations) {
            String toolResult = executeTool(toolInvocation.functionName(), toolInvocation.argumentsJson(), username);
            log.info("【智谱AI官方Agent】补充工具执行结果: {}", toolResult);
            results.add(toolResult);
        }
        return String.join("\n", results);
    }

    private List<ToolInvocation> extractToolInvocations(ChatMessage assistantMessage) {
        List<ToolInvocation> invocations = new ArrayList<>();

        if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
            for (ToolCalls toolCall : assistantMessage.getToolCalls()) {
                String functionName = toolCall.getFunction().getName();
                String arguments = toJsonArguments(toolCall.getFunction().getArguments());
                invocations.add(new ToolInvocation(toolCall.getId(), functionName, arguments));
            }
        }
        return invocations;
    }

    private String toJsonArguments(Object argumentsObj) {
        if (argumentsObj == null) {
            return "{}";
        }
        if (argumentsObj instanceof String argumentsText) {
            return argumentsText;
        }
        try {
            return objectMapper.writeValueAsString(argumentsObj);
        } catch (Exception e) {
            log.warn("【智谱AI官方Agent】工具参数序列化失败，回退 toString: {}", argumentsObj, e);
            return argumentsObj.toString();
        }
    }

    private void logResponse(String phase, ChatCompletionResponse response) {
        if (response == null) {
            log.error("【智谱AI官方Agent】{} 返回为空", phase);
            return;
        }
        int choiceSize = response.getData() != null && response.getData().getChoices() != null
                ? response.getData().getChoices().size()
                : 0;
        log.info("【智谱AI官方Agent】{} 响应: success={}, code={}, msg={}, choices={}",
                phase, response.isSuccess(), response.getCode(), response.getMsg(), choiceSize);
    }

    private String fallbackOrFailure(String question, String username, String failureMsg) {
        return "❌ 请求失败：" + failureMsg;
    }

    private record ToolInvocation(String toolCallId, String functionName, String argumentsJson) {
    }
}
