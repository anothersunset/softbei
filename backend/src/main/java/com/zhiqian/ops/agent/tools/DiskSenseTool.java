package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘感知工具（只读）：文件系统使用率与热点目录占用。
 */
@Component
public class DiskSenseTool implements AgentTool {
    private final LeastPrivilegeExecutor executor;

    public DiskSenseTool(LeastPrivilegeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() { return "disk_sense"; }

    @Override
    public String description() { return "采集磁盘信息：各文件系统使用率(df)与 /var/log 目录占用(du)"; }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("df", executor.runReadOnly(List.of("df", "-h")).stdout());
        r.put("du_var_log", executor.runReadOnly(List.of("du", "-h", "--max-depth=1", "/var/log")).stdout());
        return r;
    }
}
