package com.zhiqian.ops.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 根据配置选择推理客户端实现。沿用原项目 LlmClientFactory 模式：
 * 配置 provider=deepseek 且存在 apiKey 时使用真实模型，否则回退到本地 Mock。
 */
@Configuration
public class LlmClientFactory {
    private static final Logger log = LoggerFactory.getLogger(LlmClientFactory.class);

    @Bean
    public LlmClient llmClient(LlmProperties props) {
        boolean wantReal = props.getProvider() != null
                && !"mock".equalsIgnoreCase(props.getProvider())
                && props.getApiKey() != null
                && !props.getApiKey().isBlank();
        if (!wantReal) {
            log.info("using mock LLM client (no api-key configured or provider=mock)");
            return new MockLlmClient();
        }

        // 主备自动切换链：主模型 →（可选）备用模型 → Mock 兜底，服务不因模型不可用而中断。
        java.util.List<LlmClient> chain = new java.util.ArrayList<>();
        chain.add(new DeepSeekLlmClient(props));
        if (props.getFallbackProvider() != null && !props.getFallbackProvider().isBlank()
                && props.getFallbackApiKey() != null && !props.getFallbackApiKey().isBlank()) {
            LlmProperties fb = new LlmProperties();
            fb.setProvider(props.getFallbackProvider());
            fb.setBaseUrl(props.getFallbackBaseUrl() == null || props.getFallbackBaseUrl().isBlank()
                    ? props.getBaseUrl() : props.getFallbackBaseUrl());
            fb.setModel(props.getFallbackModel() == null || props.getFallbackModel().isBlank()
                    ? props.getModel() : props.getFallbackModel());
            fb.setApiKey(props.getFallbackApiKey());
            fb.setTimeoutSeconds(props.getTimeoutSeconds());
            chain.add(new DeepSeekLlmClient(fb));
            log.info("llm failover chain: {} -> {} -> mock", props.getProvider(), fb.getProvider());
        } else {
            log.info("llm failover chain: {} -> mock", props.getProvider());
        }
        chain.add(new MockLlmClient());
        return new FailoverLlmClient(chain, props.getFailoverCooldownMs());
    }
}
