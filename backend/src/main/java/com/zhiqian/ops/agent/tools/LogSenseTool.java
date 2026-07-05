package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志感知工具（只读）：拉取最近的错误级别系统日志。
 * 支持参数化按需感知：可指定 systemd 服务单元(unit) 与拉取行数(lines)，
 * 使 Agent 能「只看 nginx 最近 200 行错误日志」而非无差别全量采集。
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
    public String description() {
        return "采集日志信息：最近的错误级别 systemd 日志(journalctl -p err)；"
                + "可选 unit 限定某个服务单元、lines 指定行数(默认 100)";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("type", "string");
        unit.put("description", "systemd 服务单元名，如 nginx、sshd、mysqld；留空则采集全系统错误日志");
        Map<String, Object> lines = new LinkedHashMap<>();
        lines.put("type", "integer");
        lines.put("description", "拉取的日志行数，1~1000，默认 100");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("unit", unit);
        props.put("lines", lines);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        int lines = AgentTool.intArg(input, "lines", 100, 1, 1000);
        String unit = AgentTool.safeStrArg(input, "unit", null);
        List<String> argv = new ArrayList<>(List.of(
                "journalctl", "-p", "err", "-n", String.valueOf(lines), "--no-pager"));
        if (unit != null) {
            argv.add("-u");
            argv.add(unit);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scope", unit == null ? "system" : unit);
        r.put("lines", lines);
        r.put("journal_err", executor.runReadOnly(argv).stdout());
        return r;
    }
}
