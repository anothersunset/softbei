package com.zhiqian.ops.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * API/MCP 入口的最小鉴权配置。
 */
@Component
@ConfigurationProperties(prefix = "ops.security")
public class ApiSecurityProperties {
    /** 为空时关闭 token 校验，适用于默认 127.0.0.1 本地演示。 */
    private String apiToken = "";

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public boolean tokenEnabled() {
        return apiToken != null && !apiToken.isBlank();
    }
}
