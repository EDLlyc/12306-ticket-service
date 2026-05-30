package com.ahu.ticket.auth;

import com.ahu.ticket.common.BusinessException;
import com.ahu.ticket.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            throw new BusinessException(Result.unauthorized("无法解析当前请求"));
        }
        Object currentUsername = request.getAttribute(LoginTokenService.CURRENT_USERNAME_ATTR);
        if (currentUsername == null) {
            throw new BusinessException(Result.unauthorized("当前请求缺少登录用户上下文"));
        }
        return currentUsername.toString();
    }
}
