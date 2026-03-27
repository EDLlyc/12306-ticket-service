package com.ahu.ticket;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

public class QueryRefinerTest {

    interface QueryRefiner {
        @SystemMessage("你是一个专业的铁路系统客服问题优化专家。请将用户随意、口语化、可能不清晰的问题，重写成专业、清晰、意图明确的标准高铁客服问题，以便更准确地在规章制度库中检索。注意：只输出重写后的问题文本，不要包含任何多余的解释、前缀或标点符号。")
        String refine(@dev.langchain4j.service.UserMessage String question);
    }

    @Test
    public void testQueryRefiner() {
        // 从环境变量中读取 API Key，避免硬编码泄露
        // 运行前请设置环境变量: set ZHIPU_API_KEY=你的Key
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️ 跳过测试：未设置 ZHIPU_API_KEY 环境变量");
            return;
        }

        ChatLanguageModel chatModel = ZhipuAiChatModel.builder()
                .apiKey(apiKey)
                .model("glm-4")
                .temperature(0.7)
                .logRequests(true)
                .logResponses(true)
                .build();

        QueryRefiner queryRefiner = AiServices.create(QueryRefiner.class, chatModel);

        String[] questions = {
                "我想买张票，怎么弄？",
                "这破车能不能退票？",
                "有人吗？能不能帮我看下票？"
        };

        for (String q : questions) {
            System.out.println("=========================================");
            System.out.println("原始问题: " + q);
            String refined = queryRefiner.refine(q);
            System.out.println("优化后问题: " + refined);
            System.out.println("=========================================");
        }
    }
}
