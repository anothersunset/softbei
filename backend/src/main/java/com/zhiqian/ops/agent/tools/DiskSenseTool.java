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
 * 支持参数化：可指定 du 探查的目录 path（默认 /var/log），使 Agent 能按需深挖具体分区。
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
    public String description() {
        return "采集磁盘信息：各文件系统使用率(df)与指定目录占用(du)；可选 path 指定探查目录(默认 /var/log)";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "du 探查的绝对目录路径（非绝对路径将回退默认值）");
        path.put("pattern", "^/");
        path.put("default", "/var/log");
        path.put("examples", List.of("/var/log", "/data", "/tmp", "/home"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", path);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String path = AgentTool.safeStrArg(input, "path", "/var/log");
        // 仅接受绝对路径，避免相对路径歧义；清洗后若非 / 开头则回退默认
        if (!path.startsWith("/")) {
            path = "/var/log";
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("df", executor.runReadOnly(List.of("df", "-h")).stdout());
        r.put("du_target", path);
        r.put("du", executor.runReadOnly(List.of("du", "-h", "--max-depth=1", path)).stdout());
        return r;
    }
}
