package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出侧横切脱敏：执行输出、接口响应、SSE 展示与审计视图统一处理敏感信息。
 */
@Component
public class SensitiveDataSanitizer {
    private final List<Rule> rules = new ArrayList<>();

    public SensitiveDataSanitizer(RiskRuleLoader loader) {
        for (GuardRules.SensitivePattern pattern : loader.rules().getSensitivePatterns()) {
            if (pattern.getRegex() == null || pattern.getRegex().isBlank()) {
                continue;
            }
            String replacement = pattern.getReplacement() == null ? "***" : pattern.getReplacement();
            rules.add(new Rule(Pattern.compile(pattern.getRegex()), replacement));
        }
        if (rules.isEmpty()) {
            rules.add(new Rule(Pattern.compile("(?i)(password|passwd|pwd)\\s*[:=]\\s*[^\\s]+"), "$1=***"));
            rules.add(new Rule(Pattern.compile("(?i)(token|secret|access_key|sk)\\s*[:=]\\s*[^\\s]+"), "$1=***"));
        }
    }

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text;
        for (Rule rule : rules) {
            out = rule.pattern.matcher(out).replaceAll(rule.replacement);
        }
        return out;
    }

    private record Rule(Pattern pattern, String replacement) {}
}
