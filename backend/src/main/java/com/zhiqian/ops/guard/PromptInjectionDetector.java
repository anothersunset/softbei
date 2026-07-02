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
     * 规则层前置规范化：去控制/零宽字符、Unicode 兼容归一化，
     * 并解码常见混淆编码（\\xNN、\\ooo 八进制、%XX URL 编码、0xNN 空格分隔 hex）。
     * 这不是替代 LLM 语义判断，而是先把规则层能稳定覆盖的绕过手法收敛到可复现路径。
     */
    String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}\\u200B\\u200C\\u200D\\uFEFF]", "");

        // 1) URL 解码（%XX）：攻击者可利用 %2F 代替 /、%20 代替空格等绕过字面匹配
        s = decodeUrlEncoding(s);

        // 2) 空格分隔 hex 解码（0xNN 0xNNNN）：攻击者可逐字节编码命令
        s = decodeSpaceDelimitedHex(s);

        // 3) C/Java 八进制转义（\\ooo）：攻击者可利用 \\162\\155 代替 rm
        s = decodeOctalEscapes(s);

        // 4) \\xNN 十六进制转义
        s = decodeHexEscapes(s);

        return s.replaceAll("\\s+", " ").trim();
    }

    /** URL 编码解码：%XX → 对应字符（仅解码可打印 ASCII），保持非 %XX 序列不变。 */
    private String decodeUrlEncoding(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i + 2 < s.length() && s.charAt(i) == '%'
                    && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                int c = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                if (c >= 0x20 && c < 0x7f) {
                    out.append((char) c);
                } else {
                    out.append(s, i, i + 3); // 非可打印字符保留原样
                }
                i += 2;
            } else {
                out.append(s.charAt(i));
            }
        }
        return out.toString();
    }

    /** 空格分隔的 hex 字节解码：0xNN 0xNNNN → 对应字符序列。 */
    private String decodeSpaceDelimitedHex(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            if (i + 3 < s.length() && s.charAt(i) == '0'
                    && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
                    && isHex(s.charAt(i + 2)) && isHex(s.charAt(i + 3))) {
                int val = Integer.parseInt(s.substring(i + 2, i + 4), 16);
                if (val >= 0x20 && val < 0x7f) {
                    out.append((char) val);
                } else {
                    out.append(s, i, i + 4);
                }
                i += 4;
                // 跳过下一个分隔符（空格/逗号等）
                while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\t')) {
                    i++;
                }
                // 继续看是否还有连续的 hex 字节
                while (i + 3 < s.length() && s.charAt(i) == '0'
                        && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
                        && isHex(s.charAt(i + 2)) && isHex(s.charAt(i + 3))) {
                    val = Integer.parseInt(s.substring(i + 2, i + 4), 16);
                    if (val >= 0x20 && val < 0x7f) {
                        out.append((char) val);
                    }
                    i += 4;
                    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\t')) {
                        i++;
                    }
                }
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** C/Java 八进制转义解码：\\ooo（1-3 位八进制数字）→ 对应字符。 */
    private String decodeOctalEscapes(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()
                    && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '7') {
                int end = i + 1;
                while (end < s.length() && end < i + 4
                        && s.charAt(end) >= '0' && s.charAt(end) <= '7') {
                    end++;
                }
                int val = Integer.parseInt(s.substring(i + 1, end), 8);
                if (val >= 0x20 && val < 0x7f) {
                    out.append((char) val);
                } else {
                    out.append(s, i, end);
                }
                i = end - 1;
            } else {
                out.append(s.charAt(i));
            }
        }
        return out.toString();
    }

    /** \\xNN 十六进制转义解码。 */
    private String decodeHexEscapes(String s) {
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
        return decoded.toString();
    }

    private boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
