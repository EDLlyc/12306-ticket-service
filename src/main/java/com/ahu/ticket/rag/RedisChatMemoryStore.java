package com.ahu.ticket.rag;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 双层记忆架构 —— 短期精确记忆 + 长期摘要记忆 (面试核心亮点)
 *
 * 设计理念：模仿人类大脑的"工作记忆 + 长期记忆"双层结构
 *
 * ┌────────────────────────────────────────────────────────────┐
 * │  短期记忆 (Working Memory)                                  │
 * │  → MessageWindowChatMemory 滑动窗口保留最近 10 轮精确对话    │
 * │  → Redis Key: chat:memory:{sessionId}   TTL: 30 分钟       │
 * ├────────────────────────────────────────────────────────────┤
 * │  长期记忆 (Long-term Summary)                               │
 * │  → 当滑动窗口淘汰旧消息时，LLM 自动将其压缩为一段摘要       │
 * │  → 摘要作为 SystemMessage 前缀注入每轮对话                  │
 * │  → Redis Key: chat:summary:{sessionId}  TTL: 24 小时       │
 * └────────────────────────────────────────────────────────────┘
 *
 * 面试话术要点：
 * 1. 为什么需要双层？—— 纯滑动窗口会丢失早期关键信息，比如"用户之前说要退儿童票"
 * 2. 为什么用 LLM 做摘要？—— 人工写规则提取不了语义，大模型天然擅长总结压缩
 * 3. 摘要怎么注入？—— 作为 SystemMessage 前缀，大模型每轮都能看到历史概要
 * 4. 性能开销？—— 摘要只在消息被淘汰时触发一次，不是每轮都调用
 *
 * 类似架构：LangChain(Python) 的 ConversationSummaryBufferMemory
 */
@Slf4j
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    // ============ Redis Key 前缀 ============
    /** 短期记忆 Key 前缀 */
    private static final String MEMORY_PREFIX = "chat:memory:";
    /** 长期摘要 Key 前缀 */
    private static final String SUMMARY_PREFIX = "chat:summary:";

    // ============ TTL 配置 ============
    /** 短期记忆 TTL：30 分钟 */
    private static final long MEMORY_TTL_MINUTES = 30;
    /** 长期摘要 TTL：24 小时（跨会话保留） */
    private static final long SUMMARY_TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;

    /**
     * 摘要生成用的大模型（延迟注入，由 RagServiceImpl 在 @PostConstruct 时设置）
     * 不在构造器注入是为了避免循环依赖
     */
    private ChatLanguageModel summarizer;

    /** 记录每个 session 上一次的消息数量，用于检测是否发生了窗口淘汰 */
    private final Map<String, Integer> previousMessageCount = new java.util.concurrent.ConcurrentHashMap<>();

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 设置摘要生成用的大模型（由 RagServiceImpl 调用）
     */
    public void setSummarizer(ChatLanguageModel summarizer) {
        this.summarizer = summarizer;
    }

    // ================================================================
    // ChatMemoryStore 接口实现
    // ================================================================

    /**
     * 读取记忆：短期精确消息 + 长期摘要前缀
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String memoryKey = MEMORY_PREFIX + memoryId;
        String json = redisTemplate.opsForValue().get(memoryKey);

        List<ChatMessage> messages = new ArrayList<>();

        // 第一步：读取长期摘要，作为 SystemMessage 注入
        String summary = redisTemplate.opsForValue().get(SUMMARY_PREFIX + memoryId);
        if (summary != null && !summary.isBlank()) {
            messages.add(SystemMessage.from(
                    "【历史对话摘要】以下是用户之前对话的关键信息，请在回答时参考：\n" + summary));
            log.info("【长期记忆】已加载历史摘要，sessionId={}", memoryId);
        }

        // 第二步：读取短期精确消息
        if (json != null && !json.isBlank()) {
            try {
                List<ChatMessage> stored = ChatMessageDeserializer.messagesFromJson(json);
                messages.addAll(stored);
            } catch (Exception e) {
                log.error("【短期记忆】反序列化失败，清除脏数据: {}", e.getMessage());
                redisTemplate.delete(memoryKey);
            }
        }

        return messages.isEmpty() ? Collections.emptyList() : messages;
    }

    /**
     * 写回记忆：检测窗口淘汰 → 触发 LLM 摘要压缩 → 存储短期消息
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String memoryKey = MEMORY_PREFIX + memoryId;
        String sessionId = String.valueOf(memoryId);

        if (messages == null || messages.isEmpty()) {
            redisTemplate.delete(memoryKey);
            return;
        }

        // 过滤掉我们注入的摘要 SystemMessage，只保存真实对话消息
        List<ChatMessage> realMessages = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm && sm.text().startsWith("【历史对话摘要】")) {
                continue; // 跳过我们注入的摘要，不重复存储
            }
            realMessages.add(msg);
        }

        // 检测是否发生了消息淘汰（窗口滑动）
        Integer prevCount = previousMessageCount.get(sessionId);
        if (prevCount != null && prevCount > realMessages.size()) {
            // 消息数量减少 = 窗口淘汰了旧消息 → 触发摘要压缩
            int evictedCount = prevCount - realMessages.size();
            log.info("【记忆压缩】检测到窗口淘汰 {} 条旧消息，触发 LLM 摘要压缩。sessionId={}", evictedCount, sessionId);
            triggerSummarization(sessionId, realMessages);
        }

        // 更新消息计数
        previousMessageCount.put(sessionId, realMessages.size());

        // 存储短期精确消息
        try {
            String json = ChatMessageSerializer.messagesToJson(realMessages);
            redisTemplate.opsForValue().set(memoryKey, json, MEMORY_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("【短期记忆】序列化写入失败: {}", e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(MEMORY_PREFIX + memoryId);
        redisTemplate.delete(SUMMARY_PREFIX + memoryId);
        previousMessageCount.remove(String.valueOf(memoryId));
    }

    // ================================================================
    // 长期摘要压缩核心逻辑
    // ================================================================

    /**
     * 调用大模型将当前对话压缩为摘要，并追加到已有摘要上
     */
    private void triggerSummarization(String sessionId, List<ChatMessage> currentMessages) {
        if (summarizer == null) {
            log.warn("【记忆压缩】摘要模型未设置，跳过压缩");
            return;
        }

        try {
            // 读取现有摘要
            String existingSummary = redisTemplate.opsForValue().get(SUMMARY_PREFIX + sessionId);

            // 构建要压缩的对话文本
            StringBuilder conversationText = new StringBuilder();
            if (existingSummary != null && !existingSummary.isBlank()) {
                conversationText.append("【已有摘要】\n").append(existingSummary).append("\n\n");
            }
            conversationText.append("【最近对话】\n");
            for (ChatMessage msg : currentMessages) {
                if (msg instanceof UserMessage um) {
                    conversationText.append("用户: ").append(um.singleText()).append("\n");
                } else if (msg instanceof AiMessage am) {
                    // 截断过长的 AI 回复
                    String text = am.text() != null ? am.text() : "";
                    if (text.length() > 200) text = text.substring(0, 200) + "...";
                    conversationText.append("客服: ").append(text).append("\n");
                }
            }

            // 调用大模型进行摘要压缩
            String prompt = "请将以下对话内容压缩为一段简洁的摘要（不超过150字），" +
                    "保留用户的关键意图、提到的车次/日期/站点等核心信息，去掉寒暄废话。" +
                    "只输出摘要文本，不要任何前缀。\n\n" + conversationText;

            String newSummary = summarizer.generate(prompt);

            // 存入 Redis 长期记忆
            redisTemplate.opsForValue().set(
                    SUMMARY_PREFIX + sessionId, newSummary,
                    SUMMARY_TTL_HOURS, TimeUnit.HOURS);

            log.info("【记忆压缩完成】sessionId={}, 摘要: {}", sessionId,
                    newSummary.length() > 80 ? newSummary.substring(0, 80) + "..." : newSummary);

        } catch (Exception e) {
            log.error("【记忆压缩失败】sessionId={}, error={}", sessionId, e.getMessage());
            // 压缩失败不影响主流程，降级为无摘要
        }
    }
}
