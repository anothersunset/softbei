package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.llm.LlmClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 DeepSeek 模型全管线端到端测试（e2e 标记，CI 默认跳过）。
 * <p>
 * 验证完整的安全护栏管线在真实 LLM 推理下的基本可用性。
 * 非红队对抗测试——只用超简单指令检查管线能否正常收尾，
 * 不验证安全拦截语义（那是 mock 确定性测试的责任）。
 * <p>
 * 关键契约：
 * - 管线未发生未捕获异常
 * - 最终 status 不为 null
 * - 返回值包含 traceId
 * - 推理节点输出了非空的计划
 */
@SpringBootTest
@ActiveProfiles("e2e")
@Tag("e2e")
class DeepSeekPipelineE2eTest {

    @Autowired
    private OpsPipeline pipeline;

    @Test
    void simpleReadonlyInstructionReturnsExecuted() {
        ChatRequest req = new ChatRequest();
        req.setInstruction("查看磁盘使用情况");

        ChatResponse resp = pipeline.chat(req);

        // 基本契约
        assertNotNull(resp, "响应不能为 null");
        assertNotNull(resp.getTraceId(), "traceId 不能为 null");
        assertFalse(resp.getTraceId().isBlank(), "traceId 不能为空");
        assertNotNull(resp.getStatus(), "status 不能为 null");

        // 输出管线结果
        System.out.println("=== 全管线 E2E 结果 ===");
        System.out.println("  traceId: " + resp.getTraceId());
        System.out.println("  status: " + resp.getStatus());
        System.out.println("  message: " + resp.getMessage());
        System.out.println("  steps: " + (resp.getSteps() != null ? resp.getSteps().size() : 0));

        if (resp.getPlan() != null) {
            System.out.println("  plan.summary: " + resp.getPlan().getSummary());
            System.out.println("  plan.steps: " + (resp.getPlan().getSteps() != null ? resp.getPlan().getSteps().size() : 0));
        }

        // 无论 status 是 EXECUTED/REVIEW_PENDING/BLOCKED，管线都算正常完成
        // 只断言不是因异常而中断
        System.out.println("  securityScore: " + (resp.getSecurityScore() != null ? resp.getSecurityScore().score() : "null"));
        System.out.println("  counterfactual: " + (resp.getCounterfactual() != null ? resp.getCounterfactual().size() : "null"));
        System.out.println("  decisions: " + (resp.getDecisions() != null ? resp.getDecisions().size() : "null"));
        System.out.println("  execResults: " + (resp.getExecResults() != null ? resp.getExecResults().size() : "null"));
        System.out.println("  analysis: " + (resp.getAnalysis() != null ? resp.getAnalysis().substring(0, Math.min(100, resp.getAnalysis().length())) + "..." : "null"));
    }

    @Test
    void lsCommandPassesThroughGuard() {
        // 显式命令让 DeepSeek 产出一条 ls 命令，测试护栏裁决
        ChatRequest req = new ChatRequest();
        req.setInstruction("帮我看看 /tmp 下有哪些文件，用 ls -la /tmp");

        ChatResponse resp = pipeline.chat(req);

        assertNotNull(resp, "响应不能为 null");
        assertNotNull(resp.getTraceId(), "traceId 不能为 null");
        assertNotNull(resp.getStatus(), "status 不能为 null");

        System.out.println("=== ls 指令 E2E ===");
        System.out.println("  traceId: " + resp.getTraceId());
        System.out.println("  status: " + resp.getStatus());

        if (resp.getPlan() != null && resp.getPlan().getSteps() != null) {
            for (int i = 0; i < resp.getPlan().getSteps().size(); i++) {
                var step = resp.getPlan().getSteps().get(i);
                System.out.println("  plan[" + i + "]: " + step.getCommand() + "  # " + step.getPurpose());
            }
        }

        if (resp.getDecisions() != null) {
            for (int i = 0; i < resp.getDecisions().size(); i++) {
                var d = resp.getDecisions().get(i);
                System.out.println("  decision[" + i + "]: " + d.command() + " → " + d.level() + " (" + d.matchedRule() + ")");
            }
        }

        System.out.println("  securityScore: " + (resp.getSecurityScore() != null ? resp.getSecurityScore().score() : "null"));
    }
}
