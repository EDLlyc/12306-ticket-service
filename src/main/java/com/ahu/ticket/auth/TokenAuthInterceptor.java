package com.ahu.ticket.auth;

import com.ahu.ticket.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
public class TokenAuthInterceptor implements HandlerInterceptor {

    private final LoginTokenService loginTokenService;
    private final ObjectMapper objectMapper;

    public TokenAuthInterceptor(LoginTokenService loginTokenService, ObjectMapper objectMapper) {
        this.loginTokenService = loginTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            writeUnauthorized(response, Result.unauthorized("请先登录后再操作"));
            return false;
        }

        String username = loginTokenService.resolveUsername(token);
        if (username == null || username.isBlank()) {
            writeUnauthorized(response, Result.unauthorized("登录已过期，请重新登录"));
            return false;
        }

        request.setAttribute(LoginTokenService.CURRENT_USERNAME_ATTR, username);
        return true;
    }

    private String resolveToken(HttpServletRequest request) {
        String token = request.getHeader(LoginTokenService.TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            token = request.getParameter("token");
        }
        return token;
    }

    private void writeUnauthorized(HttpServletResponse response, Result<String> result) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
