package com.zhiqian.ops.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.zhiqian.ops.pipeline.ChatRequest;
import com.zhiqian.ops.pipeline.ChatResponse;
import com.zhiqian.ops.pipeline.OpsPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全护栏场景语料批量回放评测。
 * 读取 eval/scenarios.yaml，逐条过管线（provider=mock 确定性），
 * 校对最终 status，并输出安全关键指标（危险拦截率 / 注入识别率 / 误拦率）。
 */
@SpringBootTest
class ScenarioEvaluationTest {

    @Autowired
    private OpsPipeline pipeline;

    @Test
    void runScenarioCorpus() throws Exception {
        YAMLMapper mapper = new YAMLMapper();
        JsonNode root;
        try (var in = new ClassPathResource("eval/scenarios.yaml").getInputStream()) {
            root = mapper.readTree(in);
        }
        JsonNode arr = root.get("scenarios");
        assertNotNull(arr, "scenarios.yaml 必须包含 scenarios 列表");
        assertTrue(arr.isArray() && arr.size() > 0, "scenarios 列表不能为空");

        int total = 0;
        int pass = 0;
        int blockExpected = 0;
        int blockHit = 0;
        int injExpected = 0;
        int injHit = 0;
        int benignExpected = 0;
        int benignFalsePositive = 0;
        Map<String, int[]> byCat = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        for (JsonNode s : arr) {
            total++;
            String id = s.path("id").asText();
            String cat = s.path("category").asText("未分类");
            String instruction = s.path("instruction").asText();
            String expected = s.path("expectedStatus").asText();
            boolean confirmFollowup = s.path("confirmFollowup").asBoolean(false);

            ChatRequest req = new ChatRequest();
            req.setInstruction(instruction);
            ChatResponse resp = pipeline.chat(req);
            String actual = resp.getStatus();
            boolean ok = expected.equals(actual);

            if ("BLOCKED".equals(expected)) {
                blockExpected++;
                if ("BLOCKED".equals(actual)) {
                    blockHit++;
                }
            }
            if ("INJECTION_BLOCKED".equals(expected)) {
                injExpected++;
                if ("INJECTION_BLOCKED".equals(actual)) {
                    injHit++;
                }
            }
            if ("EXECUTED".equals(expected) || "REVIEW_PENDING".equals(expected)) {
                benignExpected++;
                if ("BLOCKED".equals(actual) || "INJECTION_BLOCKED".equals(actual)) {
                    benignFalsePositive++;
                }
            }

            if (ok && confirmFollowup && "REVIEW_PENDING".equals(actual)) {
                ChatRequest cf = new ChatRequest();
                cf.setInstruction(instruction);
                cf.setConfirm(true);
                cf.setTraceId(resp.getTraceId());
                ChatResponse confirmed = pipeline.chat(cf);
                if (!"EXECUTED".equals(confirmed.getStatus())) {
                    ok = false;
                    failures.add(id + " 确认后期望 EXECUTED 实际 " + confirmed.getStatus());
                }
            }

            int[] tally = byCat.computeIfAbsent(cat, k -> new int[2]);
            tally[1]++;
            if (ok) {
                pass++;
                tally[0]++;
            } else {
                failures.add(id + " [" + cat + "] 期望=" + expected + " 实际=" + actual + " 指令=" + instruction);
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add("==== softbei 安全护栏评测结果 ====");
        lines.add(String.format("总用例: %d  通过: %d  通过率: %.1f%%", total, pass, 100.0 * pass / total));
        lines.add("-- 分类通过率 --");
        for (Map.Entry<String, int[]> e : byCat.entrySet()) {
            lines.add(String.format("  %-10s %d/%d", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        lines.add("-- 安全关键指标 --");
        lines.add(String.format("  危险命令拦截率: %d/%d", blockHit, blockExpected));
        lines.add(String.format("  提示词注入识别率: %d/%d", injHit, injExpected));
        lines.add(String.format("  正常指令误拦率: %d/%d", benignFalsePositive, benignExpected));
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
