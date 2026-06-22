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
 * 真实故障/攻击场景增补集回放评测（确定性、纯规则层、无需 Spring 上下文）。
 *
 * 在核心 injection-corpus.yaml（48 条，权威口径 48/48）之外，对
 * redteam/realworld-corpus.yaml 全量回放，验证护栏在「真实生产事故话术」下的泛化能力。
 * 预期结果完全由 risk-rules.yaml 规则确定，CI 每次运行均可复现；任意漏拦/误判都会让构建变红。
 */
class RealWorldCorpusTest {

    @Test
    void replayRealWorldCorpus() throws Exception {
        RiskRuleLoader loader = new RiskRuleLoader();
        PromptInjectionDetector detector = new PromptInjectionDetector(loader);
        IntentRiskGuard guard = new IntentRiskGuard(loader);

        YAMLMapper mapper = new YAMLMapper();
        JsonNode root;
        try (var in = new ClassPathResource("redteam/realworld-corpus.yaml").getInputStream()) {
            root = mapper.readTree(in);
        }
        JsonNode cases = root.get("cases");
        assertNotNull(cases, "realworld-corpus.yaml 必须包含 cases 列表");
        assertTrue(cases.isArray() && cases.size() > 0, "cases 不能为空");

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
                if (!ok) {
                    failures.add(id + " [injection] 期望blocked=" + expected + " 实际=" + actual + " 输入=" + input);
                }
            } else if ("command".equals(type)) {
                RiskLevel expected = RiskLevel.valueOf(c.path("expectedLevel").asText());
                RiskDecision d = guard.evaluate(input);
                RiskLevel actual = d.level();
                ok = expected == actual;
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

        System.out.println(String.format("==== softbei 真实故障场景增补集评测 ==== 总用例: %d  通过: %d", total, pass));
        assertEquals(total, pass, "存在未通过用例:" + System.lineSeparator() + String.join(System.lineSeparator(), failures));
    }
}
