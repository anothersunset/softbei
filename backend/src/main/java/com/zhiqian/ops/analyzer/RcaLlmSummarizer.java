package com.zhiqian.ops.analyzer;

import com.zhiqian.ops.inspect.InspectionFinding;
import com.zhiqian.ops.inspect.InspectionReport;
import com.zhiqian.ops.llm.LlmClient;
import org.springframework.stereotype.Component;

/**
 * 基于真实大模型的根因总结器：在规则化跨源分析(CrossSourceRca)之上，
 * 调用国产开源模型(DeepSeek/Qwen3)对结构化洞察做自然语言根因归纳。
 *
 * 设计原则：
 *  - 仅在配置了真实模型(llm.isReal())时启用；mock 环境返回 null，
 *    保证离线可复现、评测确定性不被破坏(规则化结论始终可用)。
 *  - 失败安全：任何异常都回退为 null，由调用方继续展示规则化结论，不影响主流程。
 */
@Component
public class RcaLlmSummarizer {
    private final LlmClient llm;

    public RcaLlmSummarizer(LlmClient llm) {
        this.llm = llm;
    }

    public boolean enabled() {
        return llm != null && llm.isReal();
    }

    public String providerName() {
        return llm == null ? "mock" : llm.providerName();
    }

    /** 生成自然语言根因总结；未启用真实模型或失败时返回 null。 */
    public String summarize(InspectionReport report, RcaResult rca) {
        if (!enabled() || report == null || rca == null) {
            return null;
        }
        try {
            String out = llm.chat(buildPrompt(report, rca));
            // 主备降级兜底：enabled() 只是调用前的静态判断（配置了真实 provider 就算 true），
            // 但主模型若此刻不可用，FailoverLlmClient 会在这次 chat() 内部静默降级到本地 Mock；
            // Mock 返回的是固定运维计划 JSON，不是自然语言根因总结，绝不能当作 llmSummary 使用。
            // providerName() 反映"这次调用实际生效"的委托，此处据此做调用后的兜底判断。
            String actualProvider = llm.providerName();
            if (actualProvider != null && actualProvider.toLowerCase(java.util.Locale.ROOT).contains("mock")) {
                return null;
            }
            return out == null || out.isBlank() ? null : out.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String buildPrompt(InspectionReport report, RcaResult rca) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一名资深 Linux 运维专家。以下是对一台服务器的只读巡检结果与规则化跨源关联分析，")
          .append("请用中文输出一段不超过 200 字的根因总结：先给出最可能的根因，再说明指标与日志之间的关联依据，")
          .append("最后给出一句处置优先级建议。不要编造未提供的数据，不要给出会触发系统变更的具体命令。\n\n");
        sb.append("【健康评分】").append(report.healthScore()).append("/100，总体状态 ").append(report.overall()).append("\n");
        sb.append("【巡检发现(仅列告警/严重)】\n");
        if (report.findings() != null) {
            for (InspectionFinding f : report.findings()) {
                if (f == null) continue;
                if (!("WARN".equals(f.severity()) || "CRITICAL".equals(f.severity()))) continue;
                sb.append("- ").append(f.title()).append("：").append(f.observed())
                  .append("（").append(f.severity()).append("，阈值 ").append(f.threshold()).append("，证据：").append(f.evidence()).append("）\n");
            }
        }
        sb.append("【规则化跨源洞察】最高处置等级 ").append(rca.overallLevel()).append("\n");
        if (rca.insights() != null) {
            for (RcaInsight i : rca.insights()) {
                if (i == null) continue;
                sb.append("- [").append(i.level()).append("] ").append(i.title())
                  .append("：").append(i.correlation())
                  .append("（置信度 ").append(i.confidence()).append("）\n");
            }
        }
        sb.append("\n请直接给出总结，不要复述以上条目，不要使用 JSON 或代码块。");
        return sb.toString();
    }
}
