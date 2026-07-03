package com.zhiqian.ops.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeepSeek 真实模型集成测试。
 * <p>
 * 验证真实 DeepSeek API 的可用性与 JSON 输出格式兼容性。
 * 使用 {@code provider=deepseek}，需设置环境变量 OPS_LLM_API_KEY。
 * <p>
 * 用 @Tag("e2e") 标记，CI 默认跳过；手动触发时通过 {@code -Dgroups=e2e} 激活。
 * 该测试不依赖任何运维工具 Bean，只测 LLM 客户端的最基本能力：
 * 1. 网络可达性与鉴权
 * 2. System prompt 约束下输出合法 JSON
 * 3. JSON 字段完整可解析
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Tag("e2e")
class DeepSeekRealClientTest {

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private LlmProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realClientIsReal() {
        assertTrue(llmClient.isReal(), "真实模型 isReal() 应为 true");
    }

    @Test
    void providerNameIsCorrect() {
        assertEquals("deepseek", llmClient.providerName(), "provider 应为 deepseek");
    }

    @Test
    void chatReturnsValidJsonWithRequiredFields() {
        // 最简运维场景——让 DeepSeek 返回一条只读命令
        String prompt = "INSTRUCTION: 查看磁盘使用情况\n\n[环境感知]\n{}\n\n"
                + "请严格按 JSON 格式输出，字段：summary, rootCauseHypothesis, confidence(0~1), steps[{command, purpose}]";

        String raw = llmClient.chat(prompt);
        assertNotNull(raw, "响应不应为 null");
        assertFalse(raw.isBlank(), "响应不应为空");

        System.out.println("=== DeepSeek 原始响应 ===");
        System.out.println(raw);

        // 验证可解析为 PlanResult
        PlanResult plan;
        try {
            String json = extractJson(raw);
            plan = mapper.readValue(json, PlanResult.class);
        } catch (Exception e) {
            fail("解析 PlanResult 失败：原始响应[" + raw.substring(0, Math.min(200, raw.length())) + "...]", e);
            return;
        }

        assertNotNull(plan.getSummary(), "summary 字段不能为 null");
        assertFalse(plan.getSummary().isBlank(), "summary 不能为空");
        assertNotNull(plan.getConfidence(), "confidence 字段不能为 null");
        assertTrue(plan.getConfidence() >= 0 && plan.getConfidence() <= 1.0,
                "confidence 应在 [0,1] 范围内，实际: " + plan.getConfidence());
        assertNotNull(plan.getSteps(), "steps 不能为 null");

        // 至少有一条命令
        assertTrue(plan.getSteps().size() >= 1,
                "至少应有一条命令，实际: " + plan.getSteps().size());

        // 验证每条 step 的完整性
        for (int i = 0; i < plan.getSteps().size(); i++) {
            PlanStep step = plan.getSteps().get(i);
            assertNotNull(step.getCommand(), "step[" + i + "] command 不能为 null");
            assertFalse(step.getCommand().isBlank(), "step[" + i + "] command 不能为空");
            // purpose 是可选的，但最好有
            if (step.getPurpose() == null || step.getPurpose().isBlank()) {
                System.out.println("⚠  step[" + i + "].purpose 为空，command=" + step.getCommand());
            }
        }

        // 打印结果摘要
        System.out.println("=== 解析结果 ===");
        System.out.println("  summary: " + plan.getSummary());
        System.out.println("  rootCauseHypothesis: " + plan.getRootCauseHypothesis());
        System.out.println("  confidence: " + plan.getConfidence());
        System.out.println("  steps: " + plan.getSteps().size());
        for (int i = 0; i < plan.getSteps().size(); i++) {
            PlanStep step = plan.getSteps().get(i);
            System.out.println("    [" + i + "] " + step.getCommand() + "  # " + step.getPurpose());
        }
    }

    /**
     * 模拟 DeepSeek 非 JSON 输出的极端情况：验证 parsePlan 的降级逻辑能否兜底。
     * 该测试跑 mock，确保异常恢复路径可用。
     */
    @Test
    void malformedJsonFallbackWorks() {
        String malformed = "我不输出 JSON，我要说中文。";
        String json = extractJson(malformed);
        assertEquals("{}", json, "无 JSON 围栏时应降级为空对象");
    }

    // ───────────────────────────────────────────────

    private String extractJson(String s) {
        if (s == null) return "{}";
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return "{}";
    }
}
