package com.zhiqian.ops.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.guard.RiskRuleLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 红蓝对抗注入语料回放评测（确定性、纯规则层、无需 Spring 上下文）。
 *
 * 直接驱动 {@link PromptInjectionDetector} + {@link IntentRiskGuard}，对
 * redteam/injection-corpus.yaml 全量回放，统计「注入识别率 / 注入误拦率 /
 * 危险命令拦截率 / 正常命令误拦率」并断言全部命中。预期结果完全由 risk-rules.yaml
 * 规则确定，故 CI 每次运行都会复现同一组真实指标；红队漏拦会立即让构建变红。
 */
class RedTeamCorpusTest {

    private static JsonNode allCases;
    private static PromptInjectionDetector detector;
    private static IntentRiskGuard guard;

    @BeforeAll
    static void loadCorpus() throws Exception {
        RiskRuleLoader loader = new RiskRuleLoader();
        detector = new PromptInjectionDetector(loader);
        guard = new IntentRiskGuard(loader);

        YAMLMapper mapper = new YAMLMapper();
        try (var in = new ClassPathResource("redteam/injection-corpus.yaml").getInputStream()) {
            allCases = mapper.readTree(in).get("cases");
        }
        assertNotNull(allCases, "injection-corpus.yaml 必须包含 cases 列表");
        assertTrue(allCases.isArray() && allCases.size() > 0, "cases 不能为空");
    }

    // ---------- 辅助方法 ----------

    private List<JsonNode> filter(String type, Boolean expectedBlocked, RiskLevel expectedLevel) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode c : allCases) {
            if (!type.equals(c.path("type").asText())) continue;
            if (expectedBlocked != null) {
                boolean exp = c.path("expectedBlocked").asBoolean();
                if (exp != expectedBlocked) continue;
            }
            if (expectedLevel != null) {
                RiskLevel lvl;
                try {
                    lvl = RiskLevel.valueOf(c.path("expectedLevel").asText());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (lvl != expectedLevel) continue;
            }
            result.add(c);
        }
        return result;
    }

    private void assertAllPass(List<JsonNode> cases, String label, java.util.function.BiConsumer<JsonNode, List<String>> validator) {
        List<String> failures = new ArrayList<>();
        int pass = 0;
        for (JsonNode c : cases) {
            String id = c.path("id").asText();
            try {
                validator.accept(c, failures);
                pass++;
            } catch (AssertionError e) {
                failures.add(id + " " + e.getMessage());
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("---- " + label + " ----");
        lines.add(String.format("通过: %d/%d", pass, cases.size()));
        if (!failures.isEmpty()) {
            lines.add("失败明细:");
            failures.forEach(f -> lines.add("  x " + f));
        }
        System.out.println(String.join(System.lineSeparator(), lines));

        assertEquals(cases.size(), pass, "存在未通过用例:" + System.lineSeparator() + String.join(System.lineSeparator(), failures));
    }

    // ---------- 注入识别 ----------

    @Nested
    class InjectionDetection {

        @Test
        void should_block_when_roleHijackInjection() {
            List<JsonNode> cases = filter("injection", true, null);
            assertFalse(cases.isEmpty(), "注入正样本不能为空");
            assertAllPass(cases, "注入识别-应拦截", (c, failures) -> {
                String id = c.path("id").asText();
                String input = c.path("input").asText();
                boolean actual = detector.detect(input).blocked();
                if (!actual) {
                    failures.add(id + " [injection] 期望blocked=true 实际=" + actual + " 输入=" + input);
                }
            });
        }

        @Test
        void should_pass_when_benignLooksLikeInjection() {
            List<JsonNode> cases = filter("injection", false, null);
            assertFalse(cases.isEmpty(), "注入负样本不能为空");
            assertAllPass(cases, "注入识别-应放行", (c, failures) -> {
                String id = c.path("id").asText();
                String input = c.path("input").asText();
                boolean actual = detector.detect(input).blocked();
                if (actual) {
                    failures.add(id + " [injection] 期望blocked=false 实际=" + actual + " 输入=" + input);
                }
            });
        }
    }

    // ---------- 危险命令拦截 ----------

    @Nested
    class CommandInterception {

        @Test
        void should_block_when_redlineCommand() {
            List<JsonNode> cases = filter("command", null, RiskLevel.BLOCK);
            assertFalse(cases.isEmpty(), "危险命令正样本不能为空");
            assertAllPass(cases, "命令拦截-应BLOCK", (c, failures) -> {
                String id = c.path("id").asText();
                String input = c.path("input").asText();
                RiskDecision d = guard.evaluate(input);
                if (d.level() != RiskLevel.BLOCK) {
                    failures.add(id + " [command] 期望=BLOCK 实际=" + d.level()
                            + " (" + d.matchedRule() + ") 输入=" + input);
                }
            });
        }

        @Test
        void should_pass_when_readOnlyCommand() {
            List<JsonNode> cases = filter("command", null, RiskLevel.READONLY);
            assertFalse(cases.isEmpty(), "只读命令正样本不能为空");
            assertAllPass(cases, "命令拦截-应SAFE", (c, failures) -> {
                String id = c.path("id").asText();
                String input = c.path("input").asText();
                RiskDecision d = guard.evaluate(input);
                if (d.level() != RiskLevel.READONLY) {
                    failures.add(id + " [command] 期望=SAFE 实际=" + d.level()
                            + " (" + d.matchedRule() + ") 输入=" + input);
                }
            });
        }
    }

    // ---------- 综合统计（依赖上述 @Nested 测试结果，作为整体指标汇总）----------

    @Test
    void should_reportFullCorpusMetrics() {
        int injPos = 0, injPosHit = 0;
        int injNeg = 0, injNegFp = 0;
        int cmdBlock = 0, cmdBlockHit = 0;
        int cmdSafe = 0, cmdSafeFp = 0;
        int total = 0, pass = 0;

        for (JsonNode c : allCases) {
            total++;
            String type = c.path("type").asText();
            String input = c.path("input").asText();
            boolean ok;

            if ("injection".equals(type)) {
                boolean expected = c.path("expectedBlocked").asBoolean();
                boolean actual = detector.detect(input).blocked();
                ok = expected == actual;
                if (expected) { injPos++; if (actual) injPosHit++; }
                else { injNeg++; if (actual) injNegFp++; }
            } else if ("command".equals(type)) {
                RiskLevel expected = RiskLevel.valueOf(c.path("expectedLevel").asText());
                RiskDecision d = guard.evaluate(input);
                RiskLevel actual = d.level();
                ok = expected == actual;
                if (expected == RiskLevel.BLOCK) { cmdBlock++; if (actual == RiskLevel.BLOCK) cmdBlockHit++; }
                else if (expected == RiskLevel.READONLY) { cmdSafe++; if (actual != RiskLevel.READONLY) cmdSafeFp++; }
            } else {
                ok = false;
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
        System.out.println(String.join(System.lineSeparator(), lines));

        assertEquals(total, pass, "全量回放存在未通过用例");
    }
}
