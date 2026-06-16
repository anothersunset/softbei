package com.zhiqian.ops.inspect;

import com.zhiqian.ops.agent.ActiveTool;
import com.zhiqian.ops.agent.AgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康巡检工具：以 MCP 插件形式暴露「主动体检」能力。
 * 实现 ActiveTool，因此不会在被动感知(SENSE)阶段被自动调用，仅按需/定时触发或经 MCP tools/call 调用。
 */
@Component
public class HealthInspectTool implements ActiveTool {
    private final InspectionService inspectionService;

    public HealthInspectTool(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    @Override
    public String name() { return "health_inspect"; }

    @Override
    public String description() { return "主动巡检：只读体检磁盘/内存/负载/进程/端口/错误日志，输出健康评分与风险预警(不触发任何变更)"; }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        InspectionReport report = inspectionService.inspect();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inspectId", report.inspectId());
        out.put("traceId", report.traceId());
        out.put("timestamp", report.timestamp());
        out.put("healthScore", report.healthScore());
        out.put("overall", report.overall());
        out.put("summary", report.summary());
        List<Map<String, Object>> fs = new ArrayList<>();
        for (InspectionFinding f : report.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", f.category());
            m.put("severity", f.severity());
            m.put("title", f.title());
            m.put("observed", f.observed());
            m.put("threshold", f.threshold());
            m.put("evidence", f.evidence());
            m.put("suggestion", f.suggestion());
            fs.add(m);
        }
        out.put("findings", fs);
        out.put("sources", report.sources());
        out.put("elapsedMs", report.elapsedMs());
        return out;
    }
}
