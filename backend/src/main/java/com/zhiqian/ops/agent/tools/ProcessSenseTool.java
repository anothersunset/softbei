package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程感知工具（只读）：按 CPU 排序的 Top 进程，用于定位占用与僵尸进程。
 * 支持参数化：可选 limit 只取前 N 行（默认全量），使 Agent 能「只看 CPU 前 10 的进程」。
 */
@Component
public class ProcessSenseTool implements AgentTool {
    private final LeastPrivilegeExecutor executor;

    public ProcessSenseTool(LeastPrivilegeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() { return "process_sense"; }

    @Override
    public String description() {
        return "采集进程信息：按 CPU 占用排序的进程列表，含状态列以识别僵尸进程；可选 limit 只取前 N 个进程";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "只返回 CPU 占用前 N 个进程，1~200；留空返回全部");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("limit", limit);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String full = executor.runReadOnly(
                List.of("ps", "-eo", "pid,ppid,user,%cpu,%mem,stat,comm", "--sort=-%cpu")).stdout();
        // limit 为 0 表示不限制（默认）；>0 时在 Java 侧截断，保持 argv 不经 shell
        int limit = AgentTool.intArg(input, "limit", 0, 0, 200);
        String output = full;
        if (limit > 0) {
            String[] rows = full.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            // 保留表头 + 前 limit 行数据
            int keep = Math.min(rows.length, limit + 1);
            for (int i = 0; i < keep; i++) {
                if (i > 0) sb.append('\n');
                sb.append(rows[i]);
            }
            output = sb.toString();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("limit", limit == 0 ? "all" : limit);
        r.put("top_by_cpu", output);
        return r;
    }
}
