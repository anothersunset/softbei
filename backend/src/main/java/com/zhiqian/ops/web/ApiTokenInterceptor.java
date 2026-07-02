package com.zhiqian.ops.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 最小 API Token 校验：覆盖 REST 与 HTTP MCP 入口。
 */
@Component
public class ApiTokenInterceptor implements HandlerInterceptor {
    private final ApiSecurityProperties props;

    public ApiTokenInterceptor(ApiSecurityProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !props.tokenEnabled()) {
            return true;
        }
        String supplied = request.getHeader("X-Ops-Token");
        if (supplied == null || supplied.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                supplied = auth.substring(7).trim();
            }
        }
        if (constantTimeEquals(props.getApiToken(), supplied)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"missing or invalid X-Ops-Token\"}");
        return false;
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
