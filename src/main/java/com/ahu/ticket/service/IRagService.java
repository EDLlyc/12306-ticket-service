package com.ahu.ticket.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

public interface IRagService {
    // 上传文件让大模型“学习”
    String uploadKnowledge(MultipartFile file);

    // 清空当前知识库并从指定文件重新导入
    String reloadKnowledgeFromFile(String filePath);

    // 抓取网页正文并导出为 txt，暂不直接入知识库
    String crawlWebPageToText(String url, String username);
    
    // 向大模型提问 (同步阻塞)，支持按 sessionId 会话记忆
    String askQuestion(String sessionId, String question, String username);
    
    // 向大模型提问 (异步流式 SSE)，支持按 sessionId 会话记忆
    SseEmitter askQuestionStream(String sessionId, String question, String username);

    // 【工业级方案】响应式多智能体流
    Flux<String> askQuestionFlux(String sessionId, String question, String username);

    // [RAGAS 评估专用] 同时返回回答和检索到的上下文片段
    java.util.Map<String, Object> askQuestionWithContexts(String sessionId, String question, String username, String profile);

    // [对照回放] 同问题对比 with_memory vs no_memory，输出差异指标
    java.util.Map<String, Object> replayWithAndWithoutMemory(String sessionId, String question, String username, String profile);
}
