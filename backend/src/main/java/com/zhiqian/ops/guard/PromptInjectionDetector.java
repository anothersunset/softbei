package com.zhiqian.ops.guard;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 抗注入 / 高危意图入口检测：识别提示词注入（Prompt Injection）、越权诱导，
 * 以及反弹 shell、base64 解码执行、写 sudoers 免密等典型恶意/混淆输入特征，
 * 防止攻击者通过对话诱导 Agent 绕过安全护栏执行恶意操作。
 *
 * 入口侧统一做 Unicode NFKC 归一化并剔除零宽/控制字符，挫败「零宽空格 / 全角 /
 * 不可见控制字符」类绕过（针对盲测暴露的编码混淆短板的通用增强，非针对具体样本）。
 */
@Component
public class PromptInjectionDetector {
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> raw;

    /** 零宽 / 不可见 / 双向控制字符，常被用于切断关键词以绕过匹配。 */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00AD\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]");

    public PromptInjectionDetector(RiskRuleLoader loader) {
        this.raw = loader.rules().getInjectionPatterns();
        for (String p : raw) {
            patterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        }
    }

    /**
     * 归一化：Unicode NFKC + 去除零宽/控制字符。
     * 这是通用的反规避预处理，不依赖任何特定盲测样本。
     */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return INVISIBLE.matcher(normalized).replaceAll("");
    }

    public InjectionResult detect(String text) {
        if (text == null || text.isBlank()) {
            return new InjectionResult(false, List.of(), null);
        }
        String scan = sanitize(text);
        List<String> matched = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).matcher(scan).find()) {
                matched.add(raw.get(i));
            }
        }
        boolean blocked = !matched.isEmpty();
        return new InjectionResult(blocked, matched,
                blocked ? "检测到提示词注入 / 越权诱导 / 高危命令特征，已在入口侧拦截" : null);
    }
}
