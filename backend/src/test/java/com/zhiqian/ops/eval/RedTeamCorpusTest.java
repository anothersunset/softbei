package com.zhiqian.ops.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.RiskRuleLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 红蓝对抗注入语料回放评测（确定性、纯规则层、无需 Spring 上下文）。
 *
 * 直接驱动 {@link PromptInjectionDetector} + {@link IntentRiskGuard}，对
 * redteam/injection-corpus.yaml 全量回放，统计「注入识别率 / 注入误拦率 /
 * 危险命令拦截率 / 正常命令误拦率」并断言全部命中。预期结果完全由 risk-rules.yaml
 * 规则确定，故 CI 每次运行都会复现同一组真实指标；红队漏拦会立即让构建变红。
 */
class RedTeamCorpusTest {

    @Test
    void replayRedTeamCorpus() throws Exception {
        RiskRuleLoader loader = new RiskRuleLoader();
        PromptInjectionDetector detector = new PromptInjectionDetector(loader);
        IntentRiskGuard guard = new IntentRiskGuard(loader);

        YAMLMapper mapper = new YAMLMapper();
        JsonNode root;
        try (var in = new ClassPathResource("redteam/injection-corpus.yaml").getInputStream()) {
            root = mapper.readTree(in);
        }
        JsonNode cases = root.get("cases");
        assertNotNull(cases, "injection-corpus.yaml 必须包含 cases 列表");
        assertTrue(cases.isArray() && cases.size() > 0, "cases 不能为空");

        int injPos = 0, injPosHit = 0;     // 注入识别率
        int injNeg = 0, injNegFp = 0;      // 注入误拦率
        int cmdBlock = 0, cmdBlockHit = 0; // 危险命令拦截率
        int cmdSafe = 0, cmdSafeFp = 0;    // 正常(只读)命令误拦率
        int total = 0, pass = 0;
        List<String> failures = new ArrayList<>();

        for (JsonNode c : cases) {
            total++;
            String id = c.path("id").asText();
            String type = c.path("type").asText();
            String input = c.path("input").asText();
            boolean ok;

            if ("injection".equals(type)) {
                boolean expected = c.path("expectedBlocked").asBoolean();
                boolean actual = detector.detect(input).blocked();
                ok = expected == actual;
                if (expected) {
                    injPos++;
                    if (actual) injPosHit++;
                } else {
                    injNeg++;
                    if (actual) injNegFp++;
                }
                if (!ok) {
                    failures.add(id + " [injection] 期望blocked=" + expected + " 实际=" + actual + " 输入=" + input);
                }
            } else if ("command".equals(type)) {
                RiskLevel expected = RiskLevel.valueOf(c.path("expectedLevel").asText());
                RiskDecision d = guard.evaluate(input);
                RiskLevel actual = d.level();
                ok = expected == actual;
                if (expected == RiskLevel.BLOCK) {
                    cmdBlock++;
                    if (actual == RiskLevel.BLOCK) cmdBlockHit++;
                } else if (expected == RiskLevel.SAFE) {
                    cmdSafe++;
                    if (actual != RiskLevel.SAFE) cmdSafeFp++;
                }
                if (!ok) {
                    failures.add(id + " [command] 期望=" + expected + " 实际=" + actual
                            + " (" + d.matchedRule() + ") 输入=" + input);
                }
            } else {
                ok = false;
                failures.add(id + " 未知 type=" + type);
            }
            if (ok) pass++;
        }

        List<String> lines = new ArrayList<>();
        lines.add("==== softbei 红蓝对抗注入语料评测 ====");
        lines.add(String.format("总用例: %d  通过: %d  通过率: %.1f%%", total, pass, 100.0 * pass / total));
        lines.add(String.format("注入识别率(拦截率): %d/%d", injPosHit, injPos));
        lines.add(String.format("注入误拦率(正常->误判注入): %d/%d", injNegFp, injNeg));
        lines.add(String.format("危险命令拦截率: %d/%d", cmdBlockHit, cmdBlock));
        lines.add(String.format("正常命令误拦率: %d/%d", cmdSafeFp, cmdSafe));
        if (!failures.isEmpty()) {
            lines.add("-- 失败明细 --");
            for (String f : failures) {
                lines.add("  x " + f);
            }
        }
        System.out.println(String.join(System.lineSeparator(), lines));

        assertEquals(total, pass, "存在未通过用例:" + System.lineSeparator() + String.join(System.lineSeparator(), failures));
    }
}
