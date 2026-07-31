package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘感知工具（只读）：文件系统使用率、热点目录占用与文件级候选。
 * 支持参数化：可指定 du 探查的目录 path（默认 /var/log），使 Agent 能按需深挖具体分区。
 * 文件级候选（topFiles）不按目录深度猜测，而是按实际文件大小全局排序——
 * du --max-depth=N 只能看到「哪个子目录大」，看不到「哪个具体文件大」，
 * 靠加深度治标不治本（文件可能藏在任意层级）。
 */
@Component
public class DiskSenseTool implements AgentTool {
    private final LeastPrivilegeExecutor executor;
    private final DiskCandidateProperties props;

    public DiskSenseTool(LeastPrivilegeExecutor executor, DiskCandidateProperties props) {
        this.executor = executor;
        this.props = props;
    }

    @Override
    public String name() { return "disk_sense"; }

    @Override
    public String description() {
        return "采集磁盘信息：各文件系统使用率(df)、指定目录占用(du)与按大小排序的文件级候选(topFiles)；"
                + "可选 path 指定探查目录(默认 /var/log)";
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
        r.put("topFiles", findTopFiles(path));
        return r;
    }

    /** 按实际文件大小全局排序取 Top N（不限于某一层目录），只读操作。 */
    private List<Map<String, Object>> findTopFiles(String path) {
        List<String> argv = List.of("find", path,
                "-maxdepth", String.valueOf(props.getFindMaxDepth()),
                "-type", "f", "-printf", "%s\t%T@\t%p\n");
        String out = executor.runReadOnly(argv).stdout();
        List<FileCandidate> candidates = parseFindOutput(out);
        candidates.sort(Comparator.comparingLong(FileCandidate::sizeBytes).reversed());
        List<Map<String, Object>> top = new ArrayList<>();
        for (FileCandidate c : candidates) {
            if (top.size() >= props.getMaxCandidates()) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", c.path());
            m.put("sizeBytes", c.sizeBytes());
            m.put("mtimeEpochSeconds", c.mtimeEpochSeconds());
            top.add(m);
        }
        return top;
    }

    /** 解析 find -printf '%s\t%T@\t%p\n' 的输出：大小(字节)、修改时间(浮点 epoch 秒)、路径。 */
    private List<FileCandidate> parseFindOutput(String out) {
        List<FileCandidate> list = new ArrayList<>();
        if (out == null || out.isBlank()) {
            return list;
        }
        for (String line : out.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            try {
                long size = Long.parseLong(parts[0].trim());
                long mtime = (long) Double.parseDouble(parts[1].trim());
                String filePath = parts[2].trim();
                if (!filePath.isEmpty()) {
                    list.add(new FileCandidate(filePath, size, mtime));
                }
            } catch (NumberFormatException ignored) {
                // 解析失败的行（如 find 报错信息混入 stdout）直接跳过，不影响其余候选
            }
        }
        return list;
    }
}
