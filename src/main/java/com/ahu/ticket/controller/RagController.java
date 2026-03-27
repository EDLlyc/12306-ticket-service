package com.ahu.ticket.controller;

import com.ahu.ticket.common.Result;
import com.ahu.ticket.service.IRagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/rag")
public class RagController {

    @Autowired
    private IRagService ragService;

    // 1. 上传文件学习知识 (如：规章制度 txt 或 pdf)
    @PostMapping("/upload")
    public Result<String> uploadKnowledge(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error("请选择要上传的文件！");
        }
        return Result.success(ragService.uploadKnowledge(file));
    }

    // 2. 咨询智能客服 (同步)
    @GetMapping("/ask")
    public Result<String> askQuestion(
            @RequestParam(value = "sessionId", defaultValue = "default-session") String sessionId,
            @RequestParam("q") String question,
            @RequestParam(value = "username", defaultValue = "anonymous") String username) {
        if (question == null || question.trim().isEmpty()) {
            return Result.error("请输入你的问题。");
        }
        return Result.success(ragService.askQuestion(sessionId, question, username));
    }
    
    // 3. 咨询智能客服 (流式输出 SSE)
    @GetMapping(value = "/ask/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter askQuestionStream(
            @RequestParam(value = "sessionId", defaultValue = "default-session") String sessionId,
            @RequestParam("q") String question,
            @RequestParam(value = "username", defaultValue = "anonymous") String username) {
        
        if (question == null || question.trim().isEmpty()) {
            SseEmitter errorEmitter = new SseEmitter();
            errorEmitter.completeWithError(new IllegalArgumentException("问题不能为空"));
            return errorEmitter;
        }
        
        return ragService.askQuestionStream(sessionId, question, username);
    }

    // 4. [RAGAS 评估专用] 同时返回回答和检索到的上下文片段
    @GetMapping("/eval/ask")
    public Result<java.util.Map<String, Object>> askQuestionEval(
            @RequestParam(value = "sessionId", defaultValue = "eval-session") String sessionId,
            @RequestParam("q") String question,
            @RequestParam(value = "username", defaultValue = "eval-user") String username) {
        if (question == null || question.trim().isEmpty()) {
            return Result.error("问题不能为空");
        }
        return Result.success(ragService.askQuestionWithContexts(sessionId, question, username));
    }
}
