package com.ahu.ticket.agent;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.*;
import com.ahu.ticket.service.impl.TicketTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ZhipuOfficialAgent {

    private static final int MAX_TOKENS = 8192;
    private static final int OCR_MAX_PDF_PAGES = 12;
    private static final Pattern TOOL_CALL_BLOCK_PATTERN = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
    private static final Pattern ARG_KEY_PATTERN = Pattern.compile("<arg_key>(.*?)</arg_key>", Pattern.DOTALL);
    private static final Pattern ARG_VALUE_PATTERN = Pattern.compile("<arg_value>(.*?)</arg_value>", Pattern.DOTALL);
    private static final Pattern ORDER_SN_PATTERN = Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])");
    private static final Pattern TRAIN_NUMBER_PATTERN = Pattern.compile("(?<![A-Za-z0-9])(?:G|D|C|Z|T|K|Y|L)\\d{1,4}(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);

    @Value("${ai.zhipu.api-key}")
    private String apiKey;

    @Value("${ai.zhipu.agent-model:glm-4.5-air}")
    private String modelName;

    @Value("${ai.zhipu.policy-model:glm-5.1}")
    private String policyModelName;

    @Value("${ai.zhipu.policy-fast-model:glm-4.5-air}")
    private String policyFastModelName;

    @Value("${ai.zhipu.ocr-model:glm-4.6v}")
    private String ocrModelName;

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
            log.info("【智谱AI官方Agent】初始化成功，agentModel={}, policyModel={}, policyFastModel={}, ocrModel={}",
                    modelName, policyModelName, policyFastModelName, ocrModelName);
        } catch (Exception e) {
            log.error("【智谱AI官方Agent】初始化失败", e);
        }
    }

    public String extractTextFromScannedPdf(byte[] pdfBytes, String filename) {
        if (client == null) {
            throw new IllegalStateException("智谱AI客户端未初始化，无法执行 OCR");
        }

        try (PDDocument pdfDocument = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            int pageCount = Math.min(pdfDocument.getNumberOfPages(), OCR_MAX_PDF_PAGES);
            PDFRenderer pdfRenderer = new PDFRenderer(pdfDocument);
            StringBuilder fullText = new StringBuilder();

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                BufferedImage pageImage = pdfRenderer.renderImageWithDPI(pageIndex, 160);
                String dataUrl = toDataUrl(pageImage);
                String pageText = extractTextFromImage(dataUrl, pageIndex + 1, filename);
                if (pageText != null && !pageText.isBlank()) {
                    fullText.append(pageText.trim()).append("\n\n");
                }
            }

            if (fullText.toString().isBlank()) {
                throw new IllegalStateException("OCR 未能从扫描版 PDF 中识别出有效文字");
            }

            if (pdfDocument.getNumberOfPages() > OCR_MAX_PDF_PAGES) {
                fullText.append("【提示】该扫描 PDF 页数较多，当前仅识别前 ")
                        .append(OCR_MAX_PDF_PAGES)
                        .append(" 页内容。\n");
            }
            return fullText.toString();
        } catch (Exception e) {
            throw new IllegalStateException("扫描版 PDF OCR 失败: " + e.getMessage(), e);
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
                "当用户需要使用工具时，必须使用结构化 function call（toolCalls），禁止输出任何 <tool_call> 标签文本。" +
                "如果用户明确要买票但只给了出发地、目的地、日期，没有给车次号，必须调用 bookTicketByRoute，而不是只做余票查询。" +
                "如果用户明确按车次号退票（例如“G1001退掉”），必须调用 cancelOrderByTrainNumber，而不是 cancelOrder。" +
                "如果用户要求把某车次都退掉，调用 cancelAllOrdersByTrainNumber。" +
                "如果用户要求把自己的订单都退掉，调用 cancelAllOrders。";

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
                .model(modelName)
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
                        .model(modelName)
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

    public String generateText(String systemPrompt, String userPrompt) {
        return generateTextWithModel(policyModelName, systemPrompt, userPrompt);
    }

    public String generateAgentText(String systemPrompt, String userPrompt) {
        return generateTextWithModel(modelName, systemPrompt, userPrompt);
    }

    public String generatePolicyText(String systemPrompt, String userPrompt, boolean strongModel) {
        String targetModel = strongModel ? policyModelName : policyFastModelName;
        return generateTextWithModel(targetModel, systemPrompt, userPrompt);
    }

    public String executeToolCall(String functionName, Map<String, Object> arguments, String username) {
        return executeTool(functionName, toJsonArguments(arguments), username);
    }

    public String executeToolCall(String functionName, String argumentsJson, String username) {
        return executeTool(functionName, argumentsJson, username);
    }

    public boolean supportsTool(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return false;
        }
        return getSupportedToolNames().contains(functionName.trim());
    }

    public Set<String> getSupportedToolNames() {
        return buildToolDescriptors().stream()
                .map(ToolDescriptor::name)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public List<ToolDescriptor> getToolDescriptors() {
        return buildToolDescriptors();
    }

    public String generateTextWithModel(String model, String systemPrompt, String userPrompt) {
        try {
            if (client == null) {
                throw new IllegalStateException("客户端未初始化");
            }

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.builder()
                    .role("system")
                    .content(systemPrompt)
                    .build());
            messages.add(ChatMessage.builder()
                    .role("user")
                    .content(userPrompt)
                    .build());

            ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                    .model(model == null || model.isBlank() ? policyModelName : model)
                    .messages(messages)
                    .temperature(0.01f)
                    .maxTokens(MAX_TOKENS)
                    .build();

            ChatCompletionResponse response = client.chat().createChatCompletion(request);
            logResponse("plain_generate", response);
            if (!response.isSuccess()) {
                throw new IllegalStateException("code=" + response.getCode() + ", msg=" + response.getMsg());
            }
            if (response.getData() == null || response.getData().getChoices() == null || response.getData().getChoices().isEmpty()) {
                throw new IllegalStateException("choices 为空");
            }

            ChatMessage assistantMessage = response.getData().getChoices().get(0).getMessage();
            Object contentObj = assistantMessage != null ? assistantMessage.getContent() : null;
            String content = contentObj != null ? contentObj.toString() : null;
            log.info("【智谱AI官方Agent】plain_generate model={}, contentType={}, snippet={}",
                    model == null || model.isBlank() ? policyModelName : model,
                    contentObj == null ? "null" : contentObj.getClass().getName(),
                    content == null ? "null" : (content.length() > 120 ? content.substring(0, 120) + "..." : content));
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("content 为空");
            }
            return content;
        } catch (Exception e) {
            log.error("【智谱AI官方Agent】纯文本生成失败: {}", e.getMessage(), e);
            return null;
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

        // 1.1 cancelOrderByTrainNumber Tool
        Map<String, ChatFunctionParameterProperty> cancelByTrainProperties = new HashMap<>();
        cancelByTrainProperties.put("trainNumber", ChatFunctionParameterProperty.builder()
            .type("string").description("要退票的车次号，例如 G1001").build());
        cancelByTrainProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录用户名，必填").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("cancelOrderByTrainNumber")
                .description("当用户按车次号要求退票时调用。会优先退该用户该车次最近一张可退订单。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(cancelByTrainProperties).build())
                .build())
            .build());

        // 1.2 cancelAllOrdersByTrainNumber Tool
        Map<String, ChatFunctionParameterProperty> cancelAllByTrainProperties = new HashMap<>();
        cancelAllByTrainProperties.put("trainNumber", ChatFunctionParameterProperty.builder()
            .type("string").description("要批量退票的车次号，例如 G1001").build());
        cancelAllByTrainProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录用户名，必填").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("cancelAllOrdersByTrainNumber")
                .description("当用户明确要求将某一车次下的订单都退掉时调用。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(cancelAllByTrainProperties).build())
                .build())
            .build());

        // 1.3 cancelAllOrders Tool
        Map<String, ChatFunctionParameterProperty> cancelAllOrdersProperties = new HashMap<>();
        cancelAllOrdersProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录用户名，必填").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("cancelAllOrders")
                .description("当用户明确要求把自己可退的订单都退掉时调用。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(cancelAllOrdersProperties).build())
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
                .description("当用户询问自己某个订单的状态、是否购票成功、订单详情时调用此工具。")
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

        // 5. bookTicketByRoute Tool
        Map<String, ChatFunctionParameterProperty> routeBookProperties = new HashMap<>();
        routeBookProperties.put("date", ChatFunctionParameterProperty.builder()
            .type("string").description("出发日期，格式 yyyy-MM-dd").build());
        routeBookProperties.put("fromStation", ChatFunctionParameterProperty.builder()
            .type("string").description("出发城市或车站名称").build());
        routeBookProperties.put("toStation", ChatFunctionParameterProperty.builder()
            .type("string").description("到达城市或车站名称").build());
        routeBookProperties.put("username", ChatFunctionParameterProperty.builder()
            .type("string").description("当前登录的用户名").build());

        tools.add(ChatTool.builder()
            .type(ChatToolType.FUNCTION.value())
            .function(ChatFunction.builder()
                .name("bookTicketByRoute")
                .description("当用户想按出发地、目的地、日期直接买票，但没有明确给出车次号时调用。")
                .parameters(ChatFunctionParameters.builder()
                    .type("object").properties(routeBookProperties).build())
                .build())
            .build());

        // 6. queryMyOrders Tool
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

    private List<ToolDescriptor> buildToolDescriptors() {
        List<ToolDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ToolDescriptor("cancelOrder",
                "当用户要求退票、取消订单、撤单或不想要票时调用。支持直接传订单号，也支持只传用户名来自动退最后一张票。",
                List.of("orderSn", "username")));
        descriptors.add(new ToolDescriptor("cancelOrderByTrainNumber",
                "当用户按车次号要求退票时调用。会优先退该用户该车次最近一张可退订单。",
                List.of("trainNumber", "username")));
        descriptors.add(new ToolDescriptor("cancelAllOrdersByTrainNumber",
                "当用户明确要求将某一车次下的订单都退掉时调用。",
                List.of("trainNumber", "username")));
        descriptors.add(new ToolDescriptor("cancelAllOrders",
                "当用户明确要求把自己可退的订单都退掉时调用。",
                List.of("username")));
        descriptors.add(new ToolDescriptor("searchTrainTickets",
                "当用户询问特定日期从出发地到目的地的两地之间的列车余票或时刻表时调用此工具。",
                List.of("date", "fromStation", "toStation")));
        descriptors.add(new ToolDescriptor("queryOrderStatus",
                "当用户询问自己某个订单的状态、是否购票成功、订单详情时调用此工具。",
                List.of("orderSn")));
        descriptors.add(new ToolDescriptor("bookTicket",
                "当用户要求购买车票、买票、订票、预订某个车次时调用此工具。需要车次号和用户名。",
                List.of("trainNumber", "username")));
        descriptors.add(new ToolDescriptor("bookTicketByRoute",
                "当用户想按出发地、目的地、日期直接买票，但没有明确给出车次号时调用。",
                List.of("date", "fromStation", "toStation", "username")));
        descriptors.add(new ToolDescriptor("queryMyOrders",
                "当用户询问自己买了哪些票、我的订单列表、我的购票记录时调用此工具。",
                List.of("username")));
        return descriptors;
    }

    private String executeTool(String functionName, String arguments, String username) {
        try {
            Map<String, Object> args = parseArguments(arguments);
            
            if ("cancelOrder".equals(functionName)) {
                String orderSn = args.containsKey("orderSn") ? String.valueOf(args.get("orderSn")) : null;
                log.info("【智谱AI官方Agent】执行退票: orderSn={}, username={}", orderSn, username);
                return ticketTools.cancelOrder(orderSn, username);
            } else if ("cancelOrderByTrainNumber".equals(functionName)) {
                String trainNumber = args.containsKey("trainNumber") ? String.valueOf(args.get("trainNumber")) : null;
                log.info("【智谱AI官方Agent】执行按车次退票: trainNumber={}, username={}", trainNumber, username);
                return ticketTools.cancelOrderByTrainNumber(trainNumber, username);
            } else if ("cancelAllOrdersByTrainNumber".equals(functionName)) {
                String trainNumber = args.containsKey("trainNumber") ? String.valueOf(args.get("trainNumber")) : null;
                log.info("【智谱AI官方Agent】执行按车次批量退票: trainNumber={}, username={}", trainNumber, username);
                return ticketTools.cancelAllOrdersByTrainNumber(trainNumber, username);
            } else if ("cancelAllOrders".equals(functionName)) {
                log.info("【智谱AI官方Agent】执行全量批量退票: username={}", username);
                return ticketTools.cancelAllOrders(username);
            } else if ("searchTrainTickets".equals(functionName)) {
                String date = String.valueOf(args.get("date"));
                String fromStation = String.valueOf(args.get("fromStation"));
                String toStation = String.valueOf(args.get("toStation"));
                return ticketTools.searchTrainTickets(date, fromStation, toStation);
            } else if ("queryOrderStatus".equals(functionName)) {
                String orderSn = String.valueOf(args.get("orderSn"));
                return ticketTools.queryOrderStatus(orderSn, username);
            } else if ("bookTicket".equals(functionName)) {
                String trainNumber = String.valueOf(args.get("trainNumber"));
                return ticketTools.bookTicket(trainNumber, username);
            } else if ("bookTicketByRoute".equals(functionName)) {
                String date = String.valueOf(args.get("date"));
                String fromStation = String.valueOf(args.get("fromStation"));
                String toStation = String.valueOf(args.get("toStation"));
                return ticketTools.bookTicketByRoute(date, fromStation, toStation, username);
            } else if ("queryMyOrders".equals(functionName)) {
                return ticketTools.queryMyOrders(username);
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

    private String extractTextFromImage(String dataUrl, int pageNumber, String filename) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder()
                .role("system")
                .content("你是一个OCR文字提取助手。你的任务是尽可能完整、忠实地提取图片中的中文和英文文字。"
                        + "不要总结，不要解释，不要补充，只输出识别到的原文。若识别不到文字，输出 [EMPTY]。")
                .build());

        List<MessageContent> userContent = new ArrayList<>();
        userContent.add(MessageContent.builder()
                .type("text")
                .text("请提取这个PDF第 " + pageNumber + " 页中的全部可见文字，保持原文顺序。文件名：" + filename)
                .build());
        userContent.add(MessageContent.builder()
                .type("image_url")
                .imageUrl(ImageUrl.builder().url(dataUrl).build())
                .build());

        messages.add(ChatMessage.builder()
                .role("user")
                .content(userContent)
                .build());

        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(ocrModelName)
                .messages(messages)
                .temperature(0.01f)
                .maxTokens(MAX_TOKENS)
                .build();

        ChatCompletionResponse response = client.chat().createChatCompletion(request);
        logResponse("ocr_page_" + pageNumber, response);

        if (!response.isSuccess()) {
            throw new IllegalStateException("OCR 模型调用失败: " + response.getMsg());
        }

        Object content = response.getData().getChoices().get(0).getMessage().getContent();
        String text = content == null ? "" : content.toString();
        if ("[EMPTY]".equalsIgnoreCase(text.trim())) {
            return "";
        }
        return text;
    }

    private String toDataUrl(BufferedImage image) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
        return "data:image/png;base64," + base64;
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
        if (!invocations.isEmpty()) {
            return invocations;
        }

        Object contentObj = assistantMessage.getContent();
        if (contentObj != null) {
            invocations.addAll(extractTextToolInvocations(contentObj.toString()));
        }
        return invocations;
    }

    private List<ToolInvocation> extractTextToolInvocations(String content) {
        List<ToolInvocation> invocations = new ArrayList<>();
        if (content == null || content.isBlank() || !content.contains("<tool_call>")) {
            return invocations;
        }

        Matcher blockMatcher = TOOL_CALL_BLOCK_PATTERN.matcher(content);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1).trim();
            String functionName = extractFunctionName(block);
            Map<String, String> arguments = extractArguments(block);
            if (functionName == null || functionName.isBlank()) {
                log.warn("【智谱AI官方Agent】检测到文本 tool_call，但函数名为空: {}", block);
                continue;
            }
            try {
                invocations.add(new ToolInvocation(
                        "text-tool-call-" + UUID.randomUUID(),
                        functionName,
                        objectMapper.writeValueAsString(arguments)
                ));
            } catch (Exception e) {
                log.warn("【智谱AI官方Agent】文本 tool_call 参数序列化失败: {}", block, e);
            }
        }
        return invocations;
    }

    private String extractFunctionName(String block) {
        int firstArgTagIndex = block.indexOf("<arg_key>");
        String functionName = firstArgTagIndex >= 0 ? block.substring(0, firstArgTagIndex) : block;
        return functionName.replaceAll("<.*?>", "").trim();
    }

    private Map<String, String> extractArguments(String block) {
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();

        Matcher keyMatcher = ARG_KEY_PATTERN.matcher(block);
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1).trim());
        }

        Matcher valueMatcher = ARG_VALUE_PATTERN.matcher(block);
        while (valueMatcher.find()) {
            values.add(valueMatcher.group(1).trim());
        }

        Map<String, String> arguments = new LinkedHashMap<>();
        int pairCount = Math.min(keys.size(), values.size());
        for (int index = 0; index < pairCount; index++) {
            arguments.put(keys.get(index), values.get(index));
        }
        return arguments;
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
        String localResult = tryLocalFallback(question, username);
        if (localResult != null) {
            log.warn("【智谱AI官方Agent】命中本地降级兜底，failureMsg={}", failureMsg);
            return localResult;
        }
        return "❌ 请求失败：" + failureMsg;
    }

    private String tryLocalFallback(String question, String username) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalized = question.trim();
        String orderSn = extractFirstMatch(ORDER_SN_PATTERN, normalized);
        String trainNumber = extractFirstMatch(TRAIN_NUMBER_PATTERN, normalized);

        boolean refundIntent = normalized.contains("退票")
                || normalized.contains("退款")
                || normalized.contains("撤单")
                || normalized.contains("取消订单")
                || (orderSn != null)
                || (normalized.contains("退") && (trainNumber != null || normalized.contains("车票")));

        if (refundIntent) {
            if (orderSn != null) {
                return ticketTools.cancelOrder(orderSn, username);
            }
            if (trainNumber != null) {
                return ticketTools.cancelOrderByTrainNumber(trainNumber.toUpperCase(Locale.ROOT), username);
            }
            return ticketTools.cancelOrder(null, username);
        }

        boolean orderQueryIntent = normalized.contains("订单") &&
                (normalized.contains("查") || normalized.contains("查询") || normalized.contains("状态") || normalized.contains("记录"));
        if (orderQueryIntent) {
            if (orderSn != null) {
                return ticketTools.queryOrderStatus(orderSn, username);
            }
            return ticketTools.queryMyOrders(username);
        }

        boolean bookIntent = normalized.contains("买票") || normalized.contains("购票") || normalized.contains("订票") || normalized.contains("预定") || normalized.contains("预订");
        if (bookIntent && trainNumber != null) {
            return ticketTools.bookTicket(trainNumber.toUpperCase(Locale.ROOT), username);
        }

        return null;
    }

    private String extractFirstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private record ToolInvocation(String toolCallId, String functionName, String argumentsJson) {
    }

    public record ToolDescriptor(String name, String description, List<String> parameters) {
    }
}
