package com.ahu.ticket.controller;

import com.ahu.ticket.auth.LoginTokenService;
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
            return Result.badRequest("请选择要上传的文件！");
        }
        String result = ragService.uploadKnowledge(file);
        if (result == null || result.isBlank()) {
            return Result.internalError("文件解析或入库失败，请稍后重试。");
        }
        if (result.startsWith("文件解析或入库失败") || result.startsWith("❌")) {
            return Result.internalError(result);
        }
        return Result.success(result);
    }

    @PostMapping("/reload")
    public Result<String> reloadKnowledge(@RequestParam(value = "path", required = false) String filePath) {
        String result = ragService.reloadKnowledgeFromFile(filePath);
        if (result == null || result.isBlank()) {
            return Result.internalError("知识库重载失败，请稍后重试。");
        }
        if (result.startsWith("文件解析或入库失败") || result.startsWith("❌")) {
            return Result.internalError(result);
        }
        return Result.success(result);
    }

    @PostMapping("/crawl-to-txt")
    public Result<String> crawlWebPageToTxt(@RequestParam("url") String url,
                                            @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        if (url == null || url.trim().isEmpty()) {
            return Result.badRequest("请输入要抓取的网页 URL。");
        }
        String result = ragService.crawlWebPageToText(url, username);
        if (result == null || result.isBlank()) {
            return Result.internalError("网页抓取失败，请稍后重试。");
        }
        if (result.startsWith("❌")) {
            return Result.internalError(result);
        }
        return Result.success(result);
    }

    // 2. 咨询智能客服 (同步)
    @GetMapping("/ask")
    public Result<String> askQuestion(
            @RequestParam(value = "sessionId", defaultValue = "default-session") String sessionId,
        @RequestParam("q") String question,
        @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        if (question == null || question.trim().isEmpty()) {
            return Result.badRequest("请输入你的问题。");
        }
        return Result.success(ragService.askQuestion(sessionId, question, username));
    }
    
    // 3. 咨询智能客服 (流式输出 SSE)
    @GetMapping(value = "/ask/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter askQuestionStream(
            @RequestParam(value = "sessionId", defaultValue = "default-session") String sessionId,
            @RequestParam("q") String question,
            @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        
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
        @RequestParam(value = "profile", defaultValue = "strict-v2") String profile,
        @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        if (question == null || question.trim().isEmpty()) {
            return Result.badRequest("问题不能为空");
        }
        return Result.success(ragService.askQuestionWithContexts(sessionId, question, username, profile));
    }

    // 5. [对照回放] 同问题双跑 with_memory/no_memory，用于识别历史记忆污染
    @GetMapping("/eval/replay")
    public Result<java.util.Map<String, Object>> replayEval(
            @RequestParam(value = "sessionId", defaultValue = "eval-session") String sessionId,
            @RequestParam("q") String question,
        @RequestParam(value = "profile", defaultValue = "strict-v2") String profile,
        @RequestAttribute(LoginTokenService.CURRENT_USERNAME_ATTR) String username) {
        if (question == null || question.trim().isEmpty()) {
            return Result.badRequest("问题不能为空");
        }
        return Result.success(ragService.replayWithAndWithoutMemory(sessionId, question, username, profile));
    }
}
