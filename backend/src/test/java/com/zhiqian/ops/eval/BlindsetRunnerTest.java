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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲测（Blindset）运行器：对未参与规则调参的对抗改写样本做泛化评估。
 * 输出混淆矩阵（TP/FP/TN/FN + Precision/Recall/F1）。
 * 本测试如实记录通过与失败，不断言全部通过——失败项即为「已知差距」，供文档引用。
 */
class BlindsetRunnerTest {

    @Test
    void runBlindset() throws Exception {
        RiskRuleLoader loader = new RiskRuleLoader();
        PromptInjectionDetector detector = new PromptInjectionDetector(loader);
        IntentRiskGuard guard = new IntentRiskGuard(loader);

        YAMLMapper mapper = new YAMLMapper();
        JsonNode root;
        try (var in = new ClassPathResource("redteam/blindset-corpus.yaml").getInputStream()) {
            root = mapper.readTree(in);
        }
        JsonNode cases = root.get("cases");
        assertNotNull(cases, "blindset-corpus.yaml 必须包含 cases 列表");
        assertTrue(cases.isArray() && cases.size() > 0, "cases 不能为空");

        // Injection confusion matrix
        int injTP = 0, injFP = 0, injTN = 0, injFN = 0;
        // Command confusion matrix
        int cmdTP = 0, cmdFP = 0, cmdTN = 0, cmdFN = 0;
        int total = 0, pass = 0;
        List<String> failures = new ArrayList<>();

        for (JsonNode c : cases) {
            total++;
            String id = c.path("id").asText();
            String type = c.path("type").asText();
            String input = c.path("input").asText();
            String category = c.path("category").asText();
            boolean ok;

            if ("injection-positive".equals(type)) {
                boolean detected = detector.detect(input).blocked();
                if (detected) { injTP++; ok = true; }
                else { injFN++; ok = false; failures.add(id + " [FN] " + category + " | " + input); }
            } else if ("injection-negative".equals(type)) {
                boolean detected = detector.detect(input).blocked();
                if (!detected) { injTN++; ok = true; }
                else { injFP++; ok = false; failures.add(id + " [FP] " + category + " | " + input); }
            } else if ("command-dangerous".equals(type)) {
                RiskDecision d = guard.evaluate(input);
                if (d.level() == RiskLevel.BLOCK) { cmdTP++; ok = true; }
                else { cmdFN++; ok = false; failures.add(id + " [FN] " + d.level() + " | " + input); }
            } else if ("command-safe".equals(type)) {
                RiskDecision d = guard.evaluate(input);
                if (d.level() == RiskLevel.READONLY) { cmdTN++; ok = true; }
                else { cmdFP++; ok = false; failures.add(id + " [FP] " + d.level() + " (" + d.matchedRule() + ") | " + input); }
            } else {
                ok = false;
                failures.add(id + " 未知 type=" + type);
            }
            if (ok) pass++;
        }

        double injPrecision = injTP + injFP > 0 ? (double) injTP / (injTP + injFP) : 0;
        double injRecall = injTP + injFN > 0 ? (double) injTP / (injTP + injFN) : 0;
        double injF1 = injPrecision + injRecall > 0 ? 2 * injPrecision * injRecall / (injPrecision + injRecall) : 0;

        double cmdPrecision = cmdTP + cmdFP > 0 ? (double) cmdTP / (cmdTP + cmdFP) : 0;
        double cmdRecall = cmdTP + cmdFN > 0 ? (double) cmdTP / (cmdTP + cmdFN) : 0;
        double cmdF1 = cmdPrecision + cmdRecall > 0 ? 2 * cmdPrecision * cmdRecall / (cmdPrecision + cmdRecall) : 0;

        List<String> lines = new ArrayList<>();
        lines.add("==== OpsGuard 盲测泛化评估 (Blindset) ====");
        lines.add(String.format("总用例: %d  通过: %d  通过率: %.1f%%", total, pass, 100.0 * pass / total));
        lines.add("");
        lines.add("-- 注入检测混淆矩阵 --");
        lines.add(String.format("  TP=%d  FP=%d  TN=%d  FN=%d", injTP, injFP, injTN, injFN));
        lines.add(String.format("  Precision=%.3f  Recall=%.3f  F1=%.3f", injPrecision, injRecall, injF1));
        lines.add("");
        lines.add("-- 危险命令拦截混淆矩阵 --");
        lines.add(String.format("  TP=%d  FP=%d  TN=%d  FN=%d", cmdTP, cmdFP, cmdTN, cmdFN));
        lines.add(String.format("  Precision=%.3f  Recall=%.3f  F1=%.3f", cmdPrecision, cmdRecall, cmdF1));
        lines.add("");

        if (!failures.isEmpty()) {
            lines.add("-- 失败明细 (" + failures.size() + " 条) --");
            for (String f : failures) {
                lines.add("  ✗ " + f);
            }
        } else {
            lines.add("所有盲测用例全部通过！");
        }

        System.out.println(String.join(System.lineSeparator(), lines));

        // 不断言全部通过——如实记录失败项即为「已知差距」
        // assertTrue(pass == total, "存在 " + (total - pass) + " 条未通过盲测用例");
    }
}
