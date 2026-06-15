package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志感知工具（只读）：拉取最近的错误级别系统日志。
 */
@Component
public class LogSenseTool implements AgentTool {
    private final LeastPrivilegeExecutor executor;

    public LogSenseTool(LeastPrivilegeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() { return "log_sense"; }

    @Override
    public String description() { return "采集日志信息：最近的错误级别 systemd 日志(journalctl -p err)"; }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("journal_err", executor.runReadOnly(
                List.of("journalctl", "-p", "err", "-n", "100", "--no-pager")).stdout());
        return r;
    }
}
