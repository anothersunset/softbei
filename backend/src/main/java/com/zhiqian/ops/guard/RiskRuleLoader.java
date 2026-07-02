package com.zhiqian.ops.guard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * 启动时从 classpath:risk-rules.yaml 加载安全规则库。
 * 规则库与代码解耦；当前为配置化、重启生效，不宣称运行时热更新。
 */
@Component
public class RiskRuleLoader {
    private static final Logger log = LoggerFactory.getLogger(RiskRuleLoader.class);
    private final GuardRules rules;

    public RiskRuleLoader() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = new ClassPathResource("risk-rules.yaml").getInputStream()) {
            RiskRulesFile file = mapper.readValue(in, RiskRulesFile.class);
            this.rules = file.getGuard() != null ? file.getGuard() : new GuardRules();
        }
        log.info("loaded risk rules: {} blocked patterns, {} critical paths, {} critical services, {} injection patterns",
                rules.getBlockedPatterns().size(), rules.getCriticalPaths().size(),
                rules.getCriticalServices().size(), rules.getInjectionPatterns().size());
    }

    public GuardRules rules() { return rules; }

    static class RiskRulesFile {
        private GuardRules guard;
        public GuardRules getGuard() { return guard; }
        public void setGuard(GuardRules guard) { this.guard = guard; }
    }
}
