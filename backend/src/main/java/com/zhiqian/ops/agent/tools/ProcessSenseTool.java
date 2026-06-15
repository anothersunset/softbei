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
    public String description() { return "采集进程信息：按 CPU 占用排序的进程列表，含状态列以识别僵尸进程"; }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("top_by_cpu", executor.runReadOnly(
                List.of("ps", "-eo", "pid,ppid,user,%cpu,%mem,stat,comm", "--sort=-%cpu")).stdout());
        return r;
    }
}
