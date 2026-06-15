package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统基线感知工具（只读）：内核版本、运行时长与负载、内存使用。
 */
@Component
public class SystemSenseTool implements AgentTool {
    private final LeastPrivilegeExecutor executor;

    public SystemSenseTool(LeastPrivilegeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() { return "system_sense"; }

    @Override
    public String description() { return "采集系统基线信息：内核版本(uname)、运行时长与负载(uptime)、内存使用(free)"; }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uname", executor.runReadOnly(List.of("uname", "-a")).stdout());
        r.put("uptime", executor.runReadOnly(List.of("uptime")).stdout());
        r.put("memory", executor.runReadOnly(List.of("free", "-h")).stdout());
        return r;
    }
}
