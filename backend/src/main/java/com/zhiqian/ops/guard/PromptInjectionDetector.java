package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 抗注入 / 高危意图入口检测：识别提示词注入（Prompt Injection）、越权诱导，
 * 以及反弹 shell、base64 解码执行、写 sudoers 免密等典型恶意/混淆输入特征，
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
        Set<String> matched = new LinkedHashSet<>();
        String normalized = normalize(text);
        for (int i = 0; i < patterns.size(); i++) {
            Pattern p = patterns.get(i);
            if (p.matcher(text).find() || p.matcher(normalized).find()) {
                matched.add(raw.get(i));
            }
        }
        boolean blocked = !matched.isEmpty();
        return new InjectionResult(blocked, new ArrayList<>(matched),
                blocked ? "检测到提示词注入 / 越权诱导 / 高危命令特征，已在入口侧拦截" : null);
    }

    /**
     * 规则层前置规范化：去控制/零宽字符、Unicode 兼容归一化，并解码常见 \xNN 混淆。
     * 这不是替代 LLM 语义判断，而是先把规则层能稳定覆盖的绕过手法收敛到可复现路径。
     */
    String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}\\u200B\\u200C\\u200D\\uFEFF]", "");
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i + 3 < s.length() && s.charAt(i) == '\\' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
                    && isHex(s.charAt(i + 2)) && isHex(s.charAt(i + 3))) {
                decoded.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16));
                i += 3;
            } else {
                decoded.append(s.charAt(i));
            }
        }
        return decoded.toString().replaceAll("\\s+", " ").trim();
    }

    private boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
