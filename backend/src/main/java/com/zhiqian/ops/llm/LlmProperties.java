package com.zhiqian.ops.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yml 中的 ops.llm.* 配置。
 */
@Component
@ConfigurationProperties(prefix = "ops.llm")
public class LlmProperties {
    private String provider = "mock";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private String apiKey = "";
    private int timeoutSeconds = 60;
    /** 备用模型（主备自动切换）：为空表示不配置备用，仅主模型 + Mock 兜底。 */
    private String fallbackProvider = "";
    private String fallbackBaseUrl = "";
    private String fallbackModel = "";
    private String fallbackApiKey = "";
    /** 降级后回切主模型的冷却时长（毫秒）。 */
    private long failoverCooldownMs = 120_000;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getFallbackProvider() { return fallbackProvider; }
    public void setFallbackProvider(String fallbackProvider) { this.fallbackProvider = fallbackProvider; }
    public String getFallbackBaseUrl() { return fallbackBaseUrl; }
    public void setFallbackBaseUrl(String fallbackBaseUrl) { this.fallbackBaseUrl = fallbackBaseUrl; }
    public String getFallbackModel() { return fallbackModel; }
    public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
    public String getFallbackApiKey() { return fallbackApiKey; }
    public void setFallbackApiKey(String fallbackApiKey) { this.fallbackApiKey = fallbackApiKey; }
    public long getFailoverCooldownMs() { return failoverCooldownMs; }
    public void setFailoverCooldownMs(long failoverCooldownMs) { this.failoverCooldownMs = failoverCooldownMs; }
}
