package com.ahu.ticket.config;

import com.ahu.ticket.auth.CurrentUserArgumentResolver;
import com.ahu.ticket.auth.TokenAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TokenAuthInterceptor tokenAuthInterceptor;
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    public WebMvcConfig(TokenAuthInterceptor tokenAuthInterceptor,
                        CurrentUserArgumentResolver currentUserArgumentResolver) {
        this.tokenAuthInterceptor = tokenAuthInterceptor;
        this.currentUserArgumentResolver = currentUserArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthInterceptor)
                .addPathPatterns(
                        "/train/book/**",
                        "/user/orders",
                        "/user/pay",
                        "/user/refund",
                        "/rag/upload",
                        "/rag/reload",
                        "/rag/crawl-to-txt",
                        "/rag/ask",
                        "/rag/ask/stream",
                        "/rag/eval/ask",
                        "/rag/eval/replay",
                        "/rag/reactive/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
