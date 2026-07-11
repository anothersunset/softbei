package com.zhiqian.ops.analyzer;

import com.zhiqian.ops.inspect.InspectionFinding;
import com.zhiqian.ops.inspect.InspectionReport;
import com.zhiqian.ops.llm.FailoverLlmClient;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.llm.MockLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RCA 自然语言总结不能被"降级到 Mock"污染（P2）：{@code enabled()} 只看调用前的静态
 * {@code isReal()}——主模型配置了就为 true——但主模型此刻若不可用，
 * {@link FailoverLlmClient} 会在 {@code chat()} 内部静默降级到本地 Mock，
 * Mock 返回的是固定运维计划 JSON，不是自然语言根因总结，绝不能当作 llmSummary 使用。
 */
class RcaLlmSummarizerFailoverTest {

    private InspectionReport sampleReport() {
        InspectionFinding warn = new InspectionFinding(
                "disk-usage", "disk", "WARN", "磁盘使用率", "使用率", "85%", ">=80%", "df -h", "清理日志");
        return new InspectionReport("i1", "t1", "2026-01-01T00:00:00Z",
                60, "WARNING", "磁盘偏高", List.of(warn), List.of("df"), List.of(), 100);
    }

    private RcaResult sampleRca() {
        return new RcaResult("i1", "t1", "L2", "跨源关联结论", List.of());
    }

    /** 永远抛异常的"主模型"桩：模拟主模型配置了但当前不可用（如网络故障/key 失效）。 */
    private static final class AlwaysFailingRealLlm implements LlmClient {
        @Override public boolean isReal() { return true; }
        @Override public String providerName() { return "deepseek"; }
        @Override public String chat(String prompt) { throw new RuntimeException("connection refused"); }
    }

    /** 正常工作的"主模型"桩：验证未降级时摘要原样透传。 */
    private static final class WorkingRealLlm implements LlmClient {
        @Override public boolean isReal() { return true; }
        @Override public String providerName() { return "deepseek"; }
        @Override public String chat(String prompt) { return "磁盘使用率过高是根因，建议清理。"; }
    }

    @Test
    void degraded_to_mock_returns_null_instead_of_mock_plan_json() {
        FailoverLlmClient failover = new FailoverLlmClient(
                List.of(new AlwaysFailingRealLlm(), new MockLlmClient()), 60_000);
        RcaLlmSummarizer summarizer = new RcaLlmSummarizer(failover);

        // enabled() 是调用前的静态判断：主模型声称 isReal=true，所以此时仍是 true——
        // 这正是 bug 的成因：仅凭这个信号无法判断"这次调用是否真的用上了真实模型"。
        assertTrue(summarizer.enabled(), "配置了真实 provider，enabled() 在降级前应为 true");

        String summary = summarizer.summarize(sampleReport(), sampleRca());

        assertNull(summary,
                "降级到 Mock 后应返回 null，交由调用方（InspectionController）回退规则化结论，"
                        + "而不是把 Mock 的固定运维计划 JSON 当作 llmSummary 返回");
    }

    @Test
    void real_model_working_returns_actual_summary() {
        RcaLlmSummarizer summarizer = new RcaLlmSummarizer(new WorkingRealLlm());

        String summary = summarizer.summarize(sampleReport(), sampleRca());

        assertNotNull(summary, "真实模型正常工作时应返回其生成的摘要");
        assertTrue(summary.contains("磁盘"));
    }
}
