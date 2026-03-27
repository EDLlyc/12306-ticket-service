package com.ahu.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@SpringBootTest(webEnvironment = RANDOM_PORT)
public class FluxStreamingTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    public void testFluxStreaming() {
        // 模拟请求参数
        String sessionId = "test-session-flux";
        String question = "你好，请问你是谁？";
        String username = "test_user";

        // 发起响应式请求
        Flux<String> result = webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/rag/reactive/ask")
                        .queryParam("sessionId", sessionId)
                        .queryParam("question", question)
                        .queryParam("username", username)
                        .build())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody();

        // 验证返回流
        // 12306 智能客服通常会返回包含打字机效果的字符串，最后以 [DONE] 结尾
        StepVerifier.create(result)
                .expectNextCount(1) // 至少收到一个 Token
                .thenConsumeWhile(token -> !token.contains("[DONE]"))
                .expectNextMatches(token -> token.contains("[DONE]")) 
                .verifyComplete();
    }
}
