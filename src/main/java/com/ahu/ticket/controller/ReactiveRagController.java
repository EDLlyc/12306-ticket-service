package com.ahu.ticket.controller;

import com.ahu.ticket.service.IRagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@Slf4j
@RestController
@RequestMapping("/rag/reactive")
public class ReactiveRagController {

    @Autowired
    private IRagService ragService;

    /**
     * 【工业级方案】响应式 AI 客服接口
     * 采用 Flux + SSE (Server-Sent Events) 实现在高并发下的非阻塞流式输出
     * 
     * @param sessionId 会话ID，用于保持记忆
     * @param question 用户提问
     * @param username 当前登录用户名
     * @return 字符流 Flux
     */
    @GetMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(@RequestParam String sessionId,
                           @RequestParam String question,
                           @RequestParam String username) {
        log.info("【Reactive 控制器接收请求】用户: {}, 问题: {}", username, question);
        
        // 直接返回 Flux，Spring WebFlux 引擎会自动处理订阅和分片段推送
        return ragService.askQuestionFlux(sessionId, question, username)
                .doOnError(e -> log.error("Reactive 问答流发生异常", e))
                .doOnComplete(() -> log.info("Reactive 问答流推送完成"));
    }
}
