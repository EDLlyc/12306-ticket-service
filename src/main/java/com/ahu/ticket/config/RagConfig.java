package com.ahu.ticket.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.model.zhipu.ZhipuAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.zhipu.ZhipuAiStreamingChatModel;

import static java.time.Duration.ofSeconds;

@Configuration
public class RagConfig {

    @Value("${ai.zhipu.api-key}")
    private String zhipuApiKey;

    @Value("${ai.milvus.uri:http://localhost:19530}")
    private String milvusUri;

    // 1. 配置大语言模型 (Chat Model - 同步)
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return ZhipuAiChatModel.builder()
                .apiKey(zhipuApiKey)
                .model("glm-4.7-flash") // 使用最新的 GLM-4.7-Flash 模型
                .temperature(0.7)
                .connectTimeout(ofSeconds(60))
                .callTimeout(ofSeconds(60))
                .readTimeout(ofSeconds(60))
                .writeTimeout(ofSeconds(60))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    // 1.5 配置流式大语言模型 (Streaming Chat Model - 异步打字机)
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return ZhipuAiStreamingChatModel.builder()
                .apiKey(zhipuApiKey)
                .model("glm-4.7-flash")
                .temperature(0.7)
                .connectTimeout(ofSeconds(60))
                .callTimeout(ofSeconds(60))
                .readTimeout(ofSeconds(60))
                .writeTimeout(ofSeconds(60))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    // 2. 配置 Embedding 模型 (用于把文字变成向量)
    @Bean
    public EmbeddingModel embeddingModel() {
        return ZhipuAiEmbeddingModel.builder()
                .apiKey(zhipuApiKey)
                .connectTimeout(ofSeconds(60))
                .callTimeout(ofSeconds(60))
                .readTimeout(ofSeconds(60))
                .writeTimeout(ofSeconds(60))
                .build();
    }

    // 3. 配置 Milvus 向量库 (真正的企业级)
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return MilvusEmbeddingStore.builder()
                .uri(milvusUri)
                .collectionName("rules_1024")
                .dimension(1024) // 智谱 text-embedding-2 的默认维度
                .build();
    }
}
