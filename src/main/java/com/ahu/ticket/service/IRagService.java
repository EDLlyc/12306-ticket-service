package com.ahu.ticket.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

public interface IRagService {
    // 上传文件让大模型“学习”
    String uploadKnowledge(MultipartFile file);
    
    // 向大模型提问 (同步阻塞)，支持按 sessionId 会话记忆
    String askQuestion(String sessionId, String question, String username);
    
    // 向大模型提问 (异步流式 SSE)，支持按 sessionId 会话记忆
    SseEmitter askQuestionStream(String sessionId, String question, String username);

    // 【工业级方案】响应式多智能体流
    Flux<String> askQuestionFlux(String sessionId, String question, String username);

    // [RAGAS 评估专用] 同时返回回答和检索到的上下文片段
    java.util.Map<String, Object> askQuestionWithContexts(String sessionId, String question, String username);
}
