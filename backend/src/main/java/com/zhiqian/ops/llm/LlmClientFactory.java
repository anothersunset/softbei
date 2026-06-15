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
        if (wantReal) {
            log.info("using real LLM client: provider={}, model={}", props.getProvider(), props.getModel());
            return new DeepSeekLlmClient(props);
        }
        log.info("using mock LLM client (no api-key configured or provider=mock)");
        return new MockLlmClient();
    }
}
