package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 抗注入能力：识别提示词注入（Prompt Injection），
 * 防止攻击者通过对话诱导 Agent 绕过安全护栏执行恶意操作。
 */
@Component
public class PromptInjectionDetector {
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> raw;

    public PromptInjectionDetector(RiskRuleLoader loader) {
        this.raw = loader.rules().getInjectionPatterns();
        for (String p : raw) {
            patterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        }
    }

    public InjectionResult detect(String text) {
        if (text == null || text.isBlank()) {
            return new InjectionResult(false, List.of(), null);
        }
        List<String> matched = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).matcher(text).find()) {
                matched.add(raw.get(i));
            }
        }
        boolean blocked = !matched.isEmpty();
        return new InjectionResult(blocked, matched,
                blocked ? "检测到疑似提示词注入特征，已拒绝执行" : null);
    }
}
