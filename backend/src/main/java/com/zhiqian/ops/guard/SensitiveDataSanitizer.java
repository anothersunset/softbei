package com.zhiqian.ops.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 输出侧横切脱敏：执行输出、接口响应、SSE 展示与审计视图统一处理敏感信息。
 */
@Component
public class SensitiveDataSanitizer {
    private final List<Rule> rules = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

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

    @SuppressWarnings("unchecked")
    public Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return sanitize(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sanitizeValue(item));
            }
            return out;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }
        // 任意领域 POJO/record（如挂入 AgentStep 输出的 RiskDecision、OpsTask 等）：
        // 上面的递归只认识 Map/List/String，无法穿透未知类型的字段。若放任 POJO 原样返回，
        // 其内部字符串字段（一旦携带未脱敏文本）会绕过这里、直接被 Jackson 序列化进 API 响应
        // 或 MCP 工具结果——这是比"instruction 入口脱敏"更根本的漏网口子。
        // 修复：先用 Jackson 把 POJO 摊平为通用 Map/List/基础类型树（等价于先转一遍 JSON），
        // 再复用上面的递归逐层脱敏；摊平失败或原地返回同一实例时，为避免死循环直接放行原值。
        try {
            Object generic = mapper.convertValue(value, Object.class);
            if (generic == value) {
                return value;
            }
            return sanitizeValue(generic);
        } catch (Exception e) {
            return value;
        }
    }

    private record Rule(Pattern pattern, String replacement) {}
}
