package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.ActiveTool;
import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.LsofWriters;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时文件活跃度感知（主动/动作类工具，不进入自动全量感知——单次调用自带采样窗口，
 * 摊到每次普通请求上代价太高，仅供按需调用或磁盘清理复核复用）。
 *
 * 原理：同一次调用内隔 windowMs 做两次目录快照，按路径差分得到增长/缩小/新增文件，
 * 对增长中的文件补一次 lsof 定位写入进程，解决单点快照（df/du/find）看不出「谁在实时写、写多快」的问题。
 * 典型场景：磁盘候选表标了"建议清理"的文件，此刻可能已经有进程在写——单点 lsof 只能看见「有没有打开」，
 * 看不出「打开了但没怎么写」和「打开了在疯狂写」的区别，差分采样能把增速量化出来。
 */
@Component
public class IoActivitySenseTool implements ActiveTool {
    private final LeastPrivilegeExecutor executor;
    private final IoActivityProperties props;

    public IoActivitySenseTool(LeastPrivilegeExecutor executor, IoActivityProperties props) {
        this.executor = executor;
        this.props = props;
    }

    @Override
    public String name() { return "io_activity_sense"; }

    @Override
    public String description() {
        return "实时探测指定目录下文件的读写活跃度：两点差分采样(默认2秒窗口)识别正在增长/缩小/新增的文件，"
                + "并用 lsof 定位写入进程(pid/命令/用户)，附带按当前增速估算的磁盘写满 ETA；"
                + "用于判断某文件此刻是否仍在被主动写入，而不只是「曾经存在」";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "探查的绝对目录路径（非绝对路径将回退默认值）");
        path.put("pattern", "^/");
        path.put("default", "/var/log");
        path.put("examples", List.of("/var/log", "/data", "/tmp"));
        Map<String, Object> windowMs = new LinkedHashMap<>();
        windowMs.put("type", "integer");
        windowMs.put("description", "两次采样的间隔毫秒数，越短噪声越大，越长感知越慢");
        windowMs.put("default", props.getDefaultWindowMs());
        windowMs.put("minimum", props.getMinWindowMs());
        windowMs.put("maximum", props.getMaxWindowMs());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", path);
        properties.put("windowMs", windowMs);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String path = AgentTool.safeStrArg(input, "path", "/var/log");
        if (!path.startsWith("/")) {
            path = "/var/log";
        }
        int windowMs = AgentTool.intArg(input, "windowMs", props.getDefaultWindowMs(),
                props.getMinWindowMs(), props.getMaxWindowMs());

        Map<String, FileCandidate> before = snapshot(path);
        boolean interrupted = false;
        try {
            Thread.sleep(windowMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted = true;
        }
        Map<String, FileCandidate> after = snapshot(path);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", path);
        r.put("windowMs", windowMs);
        if (interrupted) {
            r.put("degraded", "采样等待被中断，差分结果可能不完整");
        }

        List<Map<String, Object>> activeFiles = new ArrayList<>();
        List<Map<String, Object>> shrinkingFiles = new ArrayList<>();
        List<Map<String, Object>> newFiles = new ArrayList<>();
        double windowSeconds = Math.max(windowMs, 1) / 1000.0;

        for (Map.Entry<String, FileCandidate> e : after.entrySet()) {
            String filePath = e.getKey();
            FileCandidate now = e.getValue();
            FileCandidate prior = before.get(filePath);
            if (prior == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", filePath);
                m.put("sizeBytes", now.sizeBytes());
                newFiles.add(m);
                continue;
            }
            long delta = now.sizeBytes() - prior.sizeBytes();
            if (delta > 0) {
                long growthBps = Math.round(delta / windowSeconds);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", filePath);
                m.put("sizeBytes", now.sizeBytes());
                m.put("growthBytesPerSec", growthBps);
                activeFiles.add(m);
            } else if (delta < 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", filePath);
                m.put("sizeBytes", now.sizeBytes());
                m.put("shrinkBytesPerSec", Math.round(-delta / windowSeconds));
                shrinkingFiles.add(m);
            }
        }

        activeFiles.sort((a, b) -> Long.compare(
                (Long) b.get("growthBytesPerSec"), (Long) a.get("growthBytesPerSec")));
        shrinkingFiles.sort((a, b) -> Long.compare(
                (Long) b.get("shrinkBytesPerSec"), (Long) a.get("shrinkBytesPerSec")));
        truncate(activeFiles);
        truncate(shrinkingFiles);
        truncate(newFiles);

        int probes = 0;
        long totalGrowthBps = 0;
        for (Map<String, Object> m : activeFiles) {
            totalGrowthBps += (Long) m.get("growthBytesPerSec");
            if (probes < props.getMaxLsofProbes()) {
                m.put("writers", writersOf(String.valueOf(m.get("path"))));
                probes++;
            }
        }

        r.put("activeFiles", activeFiles);
        r.put("shrinkingFiles", shrinkingFiles);
        r.put("newFiles", newFiles);
        r.put("diskEta", estimateDiskEta(path, totalGrowthBps));
        return r;
    }

    private void truncate(List<Map<String, Object>> list) {
        while (list.size() > props.getMaxTrackedFiles()) {
            list.remove(list.size() - 1);
        }
    }

    /** find -printf 只读快照：路径 -> (大小, mtime)，探测失败（如 find 不可用）时返回空表，不阻断主链路。 */
    private Map<String, FileCandidate> snapshot(String path) {
        Map<String, FileCandidate> out = new LinkedHashMap<>();
        try {
            List<String> argv = List.of("find", path,
                    "-maxdepth", String.valueOf(props.getFindMaxDepth()),
                    "-type", "f", "-printf", "%s\t%T@\t%p\n");
            String stdout = executor.runReadOnly(argv).stdout();
            if (stdout == null || stdout.isBlank()) {
                return out;
            }
            for (String line : stdout.split("\n")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) continue;
                try {
                    long size = Long.parseLong(parts[0].trim());
                    long mtime = (long) Double.parseDouble(parts[1].trim());
                    String filePath = parts[2].trim();
                    if (!filePath.isEmpty()) {
                        out.put(filePath, new FileCandidate(filePath, size, mtime));
                    }
                } catch (NumberFormatException ignored) {
                    // 解析失败的行（如 find 报错信息混入 stdout）直接跳过
                }
            }
        } catch (Exception ignored) {
            // 保守降级：探测失败视为「本轮无可用快照」，不抛出、不阻断主链路
        }
        return out;
    }

    /** lsof 定位写入进程：解析逻辑与磁盘候选表的增长复检共用，见 {@link LsofWriters}。 */
    private List<Map<String, Object>> writersOf(String path) {
        return LsofWriters.probe(executor, path);
    }

    /** 以「该目录本轮活跃文件的合计增速」估算所在分区的写满时间，仅供参考，非精确预测。 */
    private String estimateDiskEta(String path, long totalGrowthBps) {
        if (totalGrowthBps <= 0) {
            return null;
        }
        try {
            String stdout = executor.runReadOnly(List.of("df", "-B1", "--output=avail", path)).stdout();
            if (stdout == null) return null;
            String[] lines = stdout.trim().split("\n");
            if (lines.length < 2) return null;
            long availBytes = Long.parseLong(lines[lines.length - 1].trim());
            long etaSeconds = availBytes / totalGrowthBps;
            return path + " 所在分区按当前增速(" + totalGrowthBps + " B/s)约 "
                    + formatDuration(etaSeconds) + " 后写满(剩余 " + availBytes + " 字节，粗估非精确预测)";
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " 秒";
        if (seconds < 3600) return (seconds / 60) + " 分钟";
        if (seconds < 86400) return (seconds / 3600) + " 小时";
        return (seconds / 86400) + " 天";
    }
}
