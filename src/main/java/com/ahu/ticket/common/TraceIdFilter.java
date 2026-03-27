package com.ahu.ticket.common;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        long startNanos = System.nanoTime();
        request.setAttribute(TRACE_ID_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(TRACE_ID_KEY, traceId);
        log.info("request_start method={} uri={} query={} sessionId={} username={} orderSn={} clientIp={} userAgent={}",
                request.getMethod(),
                request.getRequestURI(),
                abbreviate(request.getQueryString(), 200),
                request.getParameter("sessionId"),
                request.getParameter("username"),
                request.getParameter("orderSn"),
                clientIp(request),
                abbreviate(request.getHeader("User-Agent"), 160));
        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error("request_error method={} uri={} sessionId={} username={} orderSn={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getParameter("sessionId"),
                    request.getParameter("username"),
                    request.getParameter("orderSn"),
                    e);
            throw e;
        } finally {
            if (request.isAsyncStarted()) {
                registerAsyncListener(request.getAsyncContext(), traceId, request, response, startNanos);
            } else {
                logRequestEnd(traceId, request, response, startNanos, "sync_complete", null);
            }
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private void registerAsyncListener(AsyncContext asyncContext, String traceId, HttpServletRequest request,
                                       HttpServletResponse response, long startNanos) {
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                logRequestEnd(traceId, request, response, startNanos, "async_complete", null);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                logRequestEnd(traceId, request, response, startNanos, "async_timeout", event.getThrowable());
            }

            @Override
            public void onError(AsyncEvent event) {
                logRequestEnd(traceId, request, response, startNanos, "async_error", event.getThrowable());
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });
    }

    private void logRequestEnd(String traceId, HttpServletRequest request, HttpServletResponse response,
                               long startNanos, String phase, Throwable throwable) {
        MDC.put(TRACE_ID_KEY, traceId);
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (throwable == null) {
            log.info("request_end phase={} method={} uri={} status={} costMs={} sessionId={} username={} orderSn={}",
                    phase,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    costMs,
                    request.getParameter("sessionId"),
                    request.getParameter("username"),
                    request.getParameter("orderSn"));
        } else {
            log.error("request_end phase={} method={} uri={} status={} costMs={} sessionId={} username={} orderSn={}",
                    phase,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    costMs,
                    request.getParameter("sessionId"),
                    request.getParameter("username"),
                    request.getParameter("orderSn"),
                    throwable);
        }
        MDC.remove(TRACE_ID_KEY);
    }

    private String resolveTraceId(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(TRACE_ID_HEADER))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
