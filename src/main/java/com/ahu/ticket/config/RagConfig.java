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
import lombok.extern.slf4j.Slf4j;

import static java.time.Duration.ofSeconds;

@Slf4j
@Configuration
public class RagConfig {

    @Value("${ai.zhipu.api-key}")
    private String zhipuApiKey;

    @Value("${ai.milvus.uri:http://localhost:19530}")
    private String milvusUri;

    @Value("${ai.zhipu.embedding-model:embedding-3}")
    private String embeddingModelName;

    @Value("${ai.zhipu.chat-model:glm-4.5-air}")
    private String chatModelName;

    @Value("${ai.zhipu.embedding-dimensions:1024}")
    private Integer embeddingDimensions;

    @Value("${ai.milvus.collection-name:rules_embedding3_1024}")
    private String milvusCollectionName;

    private void validateZhipuConfig() {
        if (zhipuApiKey == null || zhipuApiKey.isBlank() || "YOUR_ZHIPU_API_KEY_HERE".equals(zhipuApiKey)) {
            throw new IllegalStateException("ai.zhipu.api-key 未配置，请设置环境变量 ZHIPU_API_KEY。");
        }
        if ("text-embedding-2".equalsIgnoreCase(embeddingModelName)) {
            throw new IllegalStateException("ai.zhipu.embedding-model 配置无效：text-embedding-2。请改为官方模型名 embedding-2 或 embedding-3。");
        }
        if (chatModelName == null || chatModelName.isBlank()) {
            throw new IllegalStateException("ai.zhipu.chat-model 未配置，请设置环境变量 ZHIPU_CHAT_MODEL。");
        }
        log.info("【智谱模型配置】chatModel={}, embeddingModel={}, embeddingDimensions={}, milvusCollection={}",
                chatModelName, embeddingModelName, embeddingDimensions, milvusCollectionName);
    }

    // 1. 配置大语言模型 (Chat Model - 同步)
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        validateZhipuConfig();
        return ZhipuAiChatModel.builder()
                .apiKey(zhipuApiKey)
                .model(chatModelName)
                .temperature(0.01)
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
        validateZhipuConfig();
        return ZhipuAiStreamingChatModel.builder()
                .apiKey(zhipuApiKey)
                .model(chatModelName)
                .temperature(0.01)
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
        validateZhipuConfig();
        return ZhipuAiEmbeddingModel.builder()
                .apiKey(zhipuApiKey)
                .model(embeddingModelName)
                .dimensions(embeddingDimensions)
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
                .collectionName(milvusCollectionName)
                .dimension(embeddingDimensions)
                .build();
    }
}
