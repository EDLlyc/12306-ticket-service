package com.ahu.ticket.rag;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
 * │  → 仅在追问/上下文依赖场景作为 SystemMessage 前缀注入       │
 * │  → MySQL 表: t_chat_summary（按 username+sessionId 持久化） │
 * └────────────────────────────────────────────────────────────┘
 *
 * 面试话术要点：
 * 1. 为什么需要双层？—— 纯滑动窗口会丢失早期关键信息，比如"用户之前说要退儿童票"
 * 2. 为什么用 LLM 做摘要？—— 人工写规则提取不了语义，大模型天然擅长总结压缩
 * 3. 摘要怎么注入？—— 由业务层显式开启，作为 SystemMessage 前缀注入
 * 4. 性能开销？—— 摘要只在消息被淘汰时触发一次，不是每轮都调用
 *
 * 类似架构：LangChain(Python) 的 ConversationSummaryBufferMemory
 */
@Slf4j
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    public enum MemoryInjectionPolicy {
        FULL(0, false),
        FULL_WITH_SUMMARY(0, true),
        NONE(0, false),
        RECENT_2(2, false),
        RECENT_2_WITH_SUMMARY(2, true),
        RECENT_4(4, false),
        RECENT_4_WITH_SUMMARY(4, true);

        private final int maxRecentTurns;
        private final boolean includeSummary;

        MemoryInjectionPolicy(int maxRecentTurns, boolean includeSummary) {
            this.maxRecentTurns = maxRecentTurns;
            this.includeSummary = includeSummary;
        }

        public int maxRecentTurns() {
            return maxRecentTurns;
        }

        public boolean includeSummary() {
            return includeSummary;
        }

        public boolean includeAllRecentTurns() {
            return this == FULL || this == FULL_WITH_SUMMARY;
        }
    }

    // ============ Redis Key 前缀 ============
    /** 短期记忆 Key 前缀 */
    private static final String MEMORY_PREFIX = "chat:memory:";
    /** 长期摘要 Key 前缀 */
    private static final String SUMMARY_PREFIX = "chat:summary:";
    private static final String[] MEMORY_DOMAIN_PREFIXES = {"POLICY:", "TICKET:", "GENERAL:"};
    private static final String SUMMARY_SELECT_SQL = "SELECT summary FROM t_chat_summary WHERE username = ? AND session_id = ?";
    private static final String SUMMARY_UPSERT_SQL = "INSERT INTO t_chat_summary(username, session_id, summary) VALUES (?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE summary = VALUES(summary), updated_at = CURRENT_TIMESTAMP";
    private static final String SUMMARY_DELETE_SQL = "DELETE FROM t_chat_summary WHERE username = ? AND session_id = ?";

    // ============ TTL 配置 ============
    /** 短期记忆 TTL：30 分钟 */
    private static final long MEMORY_TTL_MINUTES = 30;
    /** 长期摘要读取缓存 TTL：3 小时 */
    private static final long SUMMARY_CACHE_TTL_MINUTES = 180;

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 摘要生成用的大模型（延迟注入，由 RagServiceImpl 在 @PostConstruct 时设置）
     * 不在构造器注入是为了避免循环依赖
     */
    private ChatLanguageModel summarizer;

    /** 记录每个 session 上一次的消息数量，用于检测是否发生了窗口淘汰 */
    private final Map<String, Integer> previousMessageCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> previousMessageSnapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> includeLongTermSummary = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<MemoryInjectionPolicy> memoryInjectionPolicy =
            ThreadLocal.withInitial(() -> MemoryInjectionPolicy.FULL);

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
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
     * 读取记忆：默认只读取短期精确消息，长期摘要由业务层按需开启。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        MemoryInjectionPolicy policy = memoryInjectionPolicy.get();
        boolean includeSummary = Boolean.TRUE.equals(includeLongTermSummary.get()) || policy.includeSummary();
        return getMessages(memoryId, includeSummary, policy);
    }

    public List<ChatMessage> getMessagesWithLongTermSummary(Object memoryId) {
        return getMessages(memoryId, true, MemoryInjectionPolicy.FULL_WITH_SUMMARY);
    }

    public <T> T withLongTermSummary(Supplier<T> supplier) {
        return withMemoryPolicy(MemoryInjectionPolicy.FULL_WITH_SUMMARY, supplier);
    }

    public <T> T withMemoryPolicy(MemoryInjectionPolicy policy, Supplier<T> supplier) {
        MemoryInjectionPolicy safePolicy = policy == null ? MemoryInjectionPolicy.FULL : policy;
        Boolean previous = includeLongTermSummary.get();
        MemoryInjectionPolicy previousPolicy = memoryInjectionPolicy.get();
        includeLongTermSummary.set(safePolicy.includeSummary());
        memoryInjectionPolicy.set(safePolicy);
        try {
            return supplier.get();
        } finally {
            includeLongTermSummary.set(previous);
            memoryInjectionPolicy.set(previousPolicy);
        }
    }

    private List<ChatMessage> getMessages(Object memoryId, boolean includeSummary, MemoryInjectionPolicy policy) {
        String memoryKey = MEMORY_PREFIX + memoryId;
        String json = redisTemplate.opsForValue().get(memoryKey);

        List<ChatMessage> messages = new ArrayList<>();

        if (includeSummary) {
            String summary = formatSummaryForInjection(String.valueOf(memoryId), loadSummary(String.valueOf(memoryId)));
            if (summary != null && !summary.isBlank()) {
                messages.add(SystemMessage.from(
                        "【历史记忆卡】以下是用户之前对话的关键信息，请在回答时参考：\n" + summary));
                log.info("【长期记忆】已按需加载历史摘要，sessionId={}", memoryId);
            }
        }

        if (json != null && !json.isBlank()) {
            try {
                List<ChatMessage> stored = ChatMessageDeserializer.messagesFromJson(json);
                messages.addAll(applyMemoryInjectionPolicy(stored, policy));
            } catch (Exception e) {
                log.error("【短期记忆】反序列化失败，清除脏数据: {}", e.getMessage());
                redisTemplate.delete(memoryKey);
            }
        }

        return messages.isEmpty() ? Collections.emptyList() : messages;
    }

    private List<ChatMessage> applyMemoryInjectionPolicy(List<ChatMessage> stored, MemoryInjectionPolicy policy) {
        if (stored == null || stored.isEmpty()) {
            return Collections.emptyList();
        }
        MemoryInjectionPolicy safePolicy = policy == null ? MemoryInjectionPolicy.FULL : policy;
        if (safePolicy == MemoryInjectionPolicy.NONE) {
            return Collections.emptyList();
        }
        if (safePolicy.includeAllRecentTurns()) {
            return new ArrayList<>(stored);
        }
        return limitToRecentTurns(stored, safePolicy.maxRecentTurns());
    }

    private List<ChatMessage> limitToRecentTurns(List<ChatMessage> stored, int maxRecentTurns) {
        if (stored == null || stored.isEmpty() || maxRecentTurns <= 0) {
            return Collections.emptyList();
        }
        int userTurnCount = 0;
        int startIndex = stored.size();
        for (int i = stored.size() - 1; i >= 0; i--) {
            ChatMessage message = stored.get(i);
            if (message instanceof UserMessage) {
                userTurnCount++;
                if (userTurnCount > maxRecentTurns) {
                    startIndex = i + 1;
                    break;
                }
                startIndex = i;
            }
        }
        if (startIndex >= stored.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(stored.subList(startIndex, stored.size()));
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
            if (msg instanceof SystemMessage sm
                    && (sm.text().startsWith("【历史对话摘要】") || sm.text().startsWith("【历史记忆卡】"))) {
                continue; // 跳过我们注入的摘要，不重复存储
            }
            realMessages.add(msg);
        }

        List<ChatMessage> previousSnapshot = previousMessageSnapshots.get(sessionId);
        Integer prevCount = previousMessageCount.get(sessionId);
        List<ChatMessage> evictedMessages = detectEvictedMessages(previousSnapshot, realMessages);
        if (!evictedMessages.isEmpty()) {
            log.info("【记忆压缩】检测到窗口淘汰 {} 条旧消息，触发 LLM 摘要压缩。sessionId={}",
                    evictedMessages.size(), sessionId);
            triggerSummarization(sessionId, evictedMessages);
        } else if (prevCount != null && prevCount > realMessages.size()) {
            int evictedCount = prevCount - realMessages.size();
            log.info("【记忆压缩】检测到窗口缩短 {} 条消息，触发兜底摘要压缩。sessionId={}", evictedCount, sessionId);
            triggerSummarization(sessionId, realMessages);
        }

        // 更新消息计数
        previousMessageCount.put(sessionId, realMessages.size());
        previousMessageSnapshots.put(sessionId, new ArrayList<>(realMessages));

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
        deleteSummary(String.valueOf(memoryId));
        redisTemplate.delete(summaryCacheKey(String.valueOf(memoryId)));
        previousMessageCount.remove(String.valueOf(memoryId));
        previousMessageSnapshots.remove(String.valueOf(memoryId));
    }

    // ================================================================
    // 长期摘要压缩核心逻辑
    // ================================================================

    /**
     * 调用大模型将被淘汰的旧对话压缩为摘要，并追加到已有摘要上
     */
    private void triggerSummarization(String sessionId, List<ChatMessage> evictedMessages) {
        if (summarizer == null) {
            log.warn("【记忆压缩】摘要模型未设置，跳过压缩");
            return;
        }

        try {
            // 读取现有摘要
            String existingSummary = loadSummary(sessionId);

            // 构建要压缩的对话文本
            StringBuilder conversationText = new StringBuilder();
            if (existingSummary != null && !existingSummary.isBlank()) {
                conversationText.append("【已有摘要】\n").append(existingSummary).append("\n\n");
            }
            conversationText.append("【本次被淘汰旧对话】\n");
            for (ChatMessage msg : evictedMessages) {
                if (msg instanceof UserMessage um) {
                    conversationText.append("用户: ").append(um.singleText()).append("\n");
                } else if (msg instanceof AiMessage am) {
                    String text = am.text() != null ? am.text() : "";
                    if (text.length() > 200) text = text.substring(0, 200) + "...";
                    conversationText.append("客服: ").append(text).append("\n");
                }
            }

            String prompt = buildStructuredSummaryPrompt(sessionId, conversationText.toString());

            String newSummary = summarizer.generate(prompt);

            // 存入 MySQL 长期记忆
            saveSummary(sessionId, newSummary);

            log.info("【记忆压缩完成】sessionId={}, 摘要: {}", sessionId,
                    newSummary.length() > 80 ? newSummary.substring(0, 80) + "..." : newSummary);

        } catch (Exception e) {
            log.error("【记忆压缩失败】sessionId={}, error={}", sessionId, e.getMessage());
            // 压缩失败不影响主流程，降级为无摘要
        }
    }

    private String buildStructuredSummaryPrompt(String sessionId, String conversationText) {
        MemoryDomain domain = parseMemoryDomain(sessionId);
        String domainHint = switch (domain) {
            case POLICY -> "当前会话偏政策问答，请优先保留政策主题、适用条件、例外和最近结论。";
            case TICKET -> "当前会话偏票务动作，请优先保留车次、日期、站点、订单号、待完成动作和最近执行结果。";
            case GENERAL -> "当前会话偏通用咨询，请优先保留稳定主题、已确认事实和最近结论。";
            case BASE -> "当前会话为基础会话，请优先保留稳定意图、关键事实和最近结论。";
        };
        return "请将以下历史对话压缩为一张结构化长期记忆卡，只保留后续轮次真正需要承接的信息。\n"
                + domainHint + "\n"
                + "输出要求：\n"
                + "1. 只允许输出以下字段中的非空字段，每行一个字段，不要输出解释。\n"
                + "2. 字段名固定为：稳定意图 / 已确认事实 / 待完成动作 / 最近结论 / 用户偏好约束。\n"
                + "3. 每个字段尽量压缩成短句，总长度控制在 150 字以内。\n"
                + "4. 不要保留寒暄、重复背景、无关扩展。\n\n"
                + conversationText;
    }

    private String formatSummaryForInjection(String sessionId, String summary) {
        if (summary == null || summary.isBlank()) {
            return summary;
        }
        MemoryDomain domain = parseMemoryDomain(sessionId);
        List<String> lines = Arrays.stream(summary.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.isEmpty()) {
            return summary.trim();
        }
        if (domain == MemoryDomain.POLICY) {
            List<String> filtered = lines.stream()
                    .filter(line -> line.startsWith("稳定意图")
                            || line.startsWith("已确认事实")
                            || line.startsWith("最近结论")
                            || line.startsWith("用户偏好约束"))
                    .toList();
            if (!filtered.isEmpty()) {
                return String.join("\n", filtered);
            }
        }
        return String.join("\n", lines);
    }

    private List<ChatMessage> detectEvictedMessages(List<ChatMessage> previousSnapshot, List<ChatMessage> currentMessages) {
        if (previousSnapshot == null || previousSnapshot.isEmpty() || currentMessages == null || currentMessages.isEmpty()) {
            return Collections.emptyList();
        }
        int overlap = findSuffixPrefixOverlap(previousSnapshot, currentMessages);
        int evictedSize = previousSnapshot.size() - overlap;
        if (evictedSize <= 0) {
            return Collections.emptyList();
        }
        return new ArrayList<>(previousSnapshot.subList(0, evictedSize));
    }

    private int findSuffixPrefixOverlap(List<ChatMessage> previousSnapshot, List<ChatMessage> currentMessages) {
        int maxOverlap = Math.min(previousSnapshot.size(), currentMessages.size());
        for (int overlap = maxOverlap; overlap >= 0; overlap--) {
            boolean matched = true;
            for (int i = 0; i < overlap; i++) {
                ChatMessage previous = previousSnapshot.get(previousSnapshot.size() - overlap + i);
                ChatMessage current = currentMessages.get(i);
                if (!sameMessage(previous, current)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return overlap;
            }
        }
        return 0;
    }

    private boolean sameMessage(ChatMessage left, ChatMessage right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.type() != right.type()) {
            return false;
        }
        if (left instanceof UserMessage lu && right instanceof UserMessage ru) {
            return Objects.equals(lu.singleText(), ru.singleText());
        }
        if (left instanceof AiMessage la && right instanceof AiMessage ra) {
            return Objects.equals(la.text(), ra.text());
        }
        if (left instanceof SystemMessage ls && right instanceof SystemMessage rs) {
            return Objects.equals(ls.text(), rs.text());
        }
        return Objects.equals(left.toString(), right.toString());
    }

    private String loadSummary(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        MemoryScope scope = parseMemoryScope(sessionId);
        String cachedSummary = redisTemplate.opsForValue().get(summaryCacheKey(sessionId));
        if (cachedSummary != null && !cachedSummary.isBlank()) {
            return cachedSummary;
        }
        try {
            List<String> summaries = jdbcTemplate.query(
                    SUMMARY_SELECT_SQL,
                    (rs, rowNum) -> rs.getString("summary"),
                    scope.username(),
                    scope.sessionId()
            );
            if (!summaries.isEmpty() && summaries.get(0) != null && !summaries.get(0).isBlank()) {
                cacheSummary(sessionId, summaries.get(0));
                return summaries.get(0);
            }
            MemoryScope legacyScope = parseLegacyPrefixedMemoryScope(sessionId);
            if (legacyScope != null) {
                List<String> legacySummaries = jdbcTemplate.query(
                        SUMMARY_SELECT_SQL,
                        (rs, rowNum) -> rs.getString("summary"),
                        legacyScope.username(),
                        legacyScope.sessionId()
                );
                if (!legacySummaries.isEmpty() && legacySummaries.get(0) != null && !legacySummaries.get(0).isBlank()) {
                    String legacySummary = legacySummaries.get(0);
                    saveSummary(sessionId, legacySummary);
                    cacheSummary(sessionId, legacySummary);
                    return legacySummary;
                }
            }
        } catch (Exception e) {
            log.warn("【长期记忆】从 MySQL 读取摘要失败，username={}, sessionId={}, err={}",
                    scope.username(), scope.sessionId(), e.getMessage());
        }

        // 兼容迁移：若 MySQL 没有，尝试读取旧 Redis 摘要
        String legacySummary = redisTemplate.opsForValue().get(summaryCacheKey(sessionId));
        if (legacySummary != null && !legacySummary.isBlank()) {
            saveSummary(sessionId, legacySummary);
            return legacySummary;
        }
        return null;
    }

    private void saveSummary(String sessionId, String summary) {
        if (sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }
        MemoryScope scope = parseMemoryScope(sessionId);
        try {
            jdbcTemplate.update(SUMMARY_UPSERT_SQL, scope.username(), scope.sessionId(), summary);
            cacheSummary(sessionId, summary);
        } catch (Exception e) {
            log.warn("【长期记忆】写入 MySQL 摘要失败，username={}, sessionId={}, err={}",
                    scope.username(), scope.sessionId(), e.getMessage());
        }
    }

    private void deleteSummary(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        MemoryScope scope = parseMemoryScope(sessionId);
        try {
            jdbcTemplate.update(SUMMARY_DELETE_SQL, scope.username(), scope.sessionId());
            MemoryScope legacyScope = parseLegacyPrefixedMemoryScope(sessionId);
            if (legacyScope != null) {
                jdbcTemplate.update(SUMMARY_DELETE_SQL, legacyScope.username(), legacyScope.sessionId());
            }
            redisTemplate.delete(summaryCacheKey(sessionId));
        } catch (Exception e) {
            log.warn("【长期记忆】删除 MySQL 摘要失败，username={}, sessionId={}, err={}",
                    scope.username(), scope.sessionId(), e.getMessage());
        }
    }

    private void cacheSummary(String sessionId, String summary) {
        if (sessionId == null || sessionId.isBlank() || summary == null || summary.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(summaryCacheKey(sessionId), summary, SUMMARY_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private String summaryCacheKey(String sessionId) {
        return SUMMARY_PREFIX + sessionId;
    }

    private MemoryDomain parseMemoryDomain(String memoryId) {
        String raw = memoryId == null ? "" : memoryId.trim();
        if (raw.startsWith("POLICY:")) {
            return MemoryDomain.POLICY;
        }
        if (raw.startsWith("TICKET:")) {
            return MemoryDomain.TICKET;
        }
        if (raw.startsWith("GENERAL:")) {
            return MemoryDomain.GENERAL;
        }
        return MemoryDomain.BASE;
    }

    private MemoryScope parseMemoryScope(String memoryId) {
        String raw = memoryId == null ? "" : memoryId.trim();
        String domainPrefix = extractMemoryDomainPrefix(raw);
        String rawWithoutDomain = stripMemoryDomainPrefix(raw);
        int splitIndex = rawWithoutDomain.indexOf("::");
        if (splitIndex > 0 && splitIndex < raw.length() - 2) {
            String username = rawWithoutDomain.substring(0, splitIndex).trim();
            String sessionId = rawWithoutDomain.substring(splitIndex + 2).trim();
            if (!username.isEmpty() && !sessionId.isEmpty()) {
                return new MemoryScope(username, domainPrefix + sessionId);
            }
        }
        return new MemoryScope("anonymous", rawWithoutDomain.isEmpty() ? "default-session" : domainPrefix + rawWithoutDomain);
    }

    private MemoryScope parseLegacyPrefixedMemoryScope(String memoryId) {
        String raw = memoryId == null ? "" : memoryId.trim();
        if (raw.isEmpty() || extractMemoryDomainPrefix(raw).isEmpty()) {
            return null;
        }
        int splitIndex = raw.indexOf("::");
        if (splitIndex <= 0 || splitIndex >= raw.length() - 2) {
            return null;
        }
        String username = raw.substring(0, splitIndex).trim();
        String sessionId = raw.substring(splitIndex + 2).trim();
        if (username.isEmpty() || sessionId.isEmpty()) {
            return null;
        }
        return new MemoryScope(username, sessionId);
    }

    private String extractMemoryDomainPrefix(String memoryId) {
        String raw = memoryId == null ? "" : memoryId.trim();
        for (String prefix : MEMORY_DOMAIN_PREFIXES) {
            if (raw.startsWith(prefix)) {
                return prefix;
            }
        }
        return "";
    }

    private String stripMemoryDomainPrefix(String memoryId) {
        String raw = memoryId == null ? "" : memoryId.trim();
        String prefix = extractMemoryDomainPrefix(raw);
        if (prefix.isEmpty()) {
            return raw;
        }
        return raw.substring(prefix.length()).trim();
    }

    private record MemoryScope(String username, String sessionId) {}

    private enum MemoryDomain {
        BASE,
        TICKET,
        POLICY,
        GENERAL
    }
}
