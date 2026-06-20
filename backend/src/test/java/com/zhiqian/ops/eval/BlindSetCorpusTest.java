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
 * 盲测泛化评测（generalization / out-of-distribution）。
 *
 * 与 {@link RedTeamCorpusTest} 不同：本语料 blindset-corpus.yaml 全部为「未参与规则调参」的
 * 对抗变体（编码 / 大小写 / 同形字 / 多语言 / 新措辞 / 命令变体），用于回答「换没见过的样本还能多少」。
 *
 * 因此本测试【不】断言全部命中——它如实统计混淆矩阵（TP/FP/TN/FN）与 Precision/Recall/F1
 * 并打印到控制台，真实泛化数字由 CI/本地实跑产生，再回填 docs/redteam-generalization.md。
 * 只对「语料可加载、可全量跑完」做结构性断言，避免泛化漏报误伤构建。
 */
class BlindSetCorpusTest {

    @Test
    void replayBlindSet() throws Exception {
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

        int injTp = 0, injFp = 0, injTn = 0, injFn = 0;
        int cmdTp = 0, cmdFp = 0, cmdTn = 0, cmdFn = 0;
        int cmdTotal = 0, cmdExact = 0;
        int total = 0, ran = 0;
        List<String> misses = new ArrayList<>();

        for (JsonNode c : cases) {
            total++;
            String id = c.path("id").asText();
            String type = c.path("type").asText();
            String input = c.path("input").asText();

            if ("injection".equals(type)) {
                boolean expected = c.path("expectedBlocked").asBoolean();
                boolean actual = detector.detect(input).blocked();
                if (expected && actual) injTp++;
                else if (expected && !actual) { injFn++; misses.add(id + " [漏报注入] " + input); }
                else if (!expected && actual) { injFp++; misses.add(id + " [误拦正常] " + input); }
                else injTn++;
                ran++;
            } else if ("command".equals(type)) {
                RiskLevel expected = RiskLevel.valueOf(c.path("expectedLevel").asText());
                RiskDecision d = guard.evaluate(input);
                RiskLevel actual = d.level();
                cmdTotal++;
                if (expected == actual) cmdExact++;
                else misses.add(id + " [分级偏差] 期望=" + expected + " 实际=" + actual + " " + input);
                boolean expBlock = expected == RiskLevel.BLOCK;
                boolean actBlock = actual == RiskLevel.BLOCK;
                if (expBlock && actBlock) cmdTp++;
                else if (expBlock && !actBlock) cmdFn++;
                else if (!expBlock && actBlock) cmdFp++;
                else cmdTn++;
                ran++;
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("==== softbei 盲测泛化评测（OOD） ====");
        lines.add(String.format("总样本: %d  已评测: %d", total, ran));
        lines.add("-- 注入检测（positive=应拦截）--");
        lines.add(String.format("TP=%d FP=%d TN=%d FN=%d", injTp, injFp, injTn, injFn));
        lines.add(metricLine("注入", injTp, injFp, injFn));
        lines.add("-- 危险命令拦截（positive=应BLOCK）--");
        lines.add(String.format("TP=%d FP=%d TN=%d FN=%d", cmdTp, cmdFp, cmdTn, cmdFn));
        lines.add(metricLine("危险命令", cmdTp, cmdFp, cmdFn));
        lines.add(String.format("命令精确分级准确率: %d/%d", cmdExact, cmdTotal));
        if (!misses.isEmpty()) {
            lines.add("-- 偏差明细（泛化缺口，用于回填与规则补强）--");
            for (String m : misses) lines.add("  · " + m);
        }
        System.out.println(String.join(System.lineSeparator(), lines));

        assertTrue(ran == total, "应全量评测每条盲测样本");
    }

    private static String metricLine(String name, int tp, int fp, int fn) {
        double p = (tp + fp) == 0 ? 0.0 : 100.0 * tp / (tp + fp);
        double r = (tp + fn) == 0 ? 0.0 : 100.0 * tp / (tp + fn);
        double f1 = (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        return String.format("%s  Precision=%.1f%%  Recall=%.1f%%  F1=%.1f", name, p, r, f1);
    }
}
