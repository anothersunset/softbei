package com.zhiqian.ops.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.agent.AgentStep;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推理链路溯源审计服务。
 * 每一步均以 JSONL 追加落盘（形成闭环日志），同时在内存保留近期跟踪供查询。
 * 生产化考量：
 * <ul>
 *   <li>轮转：活动文件超过 rotate-bytes 阈值时归档为 <base>-<epochMillis>.jsonl，防止无限增长；</li>
 *   <li>回扫：内存 LRU 未命中时回扫当前 + 归档 JSONL 重建单条 trace，重启后任意历史 traceId 仍可查。</li>
 * </ul>
 */
@Service
public class OpsAuditService {
    private static final Logger log = LoggerFactory.getLogger(OpsAuditService.class);
    private static final int MAX_IN_MEMORY = 200;
    private static final long DEFAULT_ROTATE_BYTES = 32L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path traceFile;
    private final SensitiveDataSanitizer sanitizer;
    private final long rotateBytes;
    private final Map<String, OpsTrace> traces = new ConcurrentHashMap<>();
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());

    public OpsAuditService(@Value("${ops.trace.file:logs/ops-trace.jsonl}") String file) {
        this(file, null, DEFAULT_ROTATE_BYTES);
    }

    public OpsAuditService(String file, SensitiveDataSanitizer sanitizer) {
        this(file, sanitizer, DEFAULT_ROTATE_BYTES);
    }

    @Autowired
    public OpsAuditService(@Value("${ops.trace.file:logs/ops-trace.jsonl}") String file,
                           SensitiveDataSanitizer sanitizer,
                           @Value("${ops.trace.rotate-bytes:33554432}") long rotateBytes) {
        this.traceFile = Paths.get(file);
        this.sanitizer = sanitizer;
        this.rotateBytes = Math.max(1024, rotateBytes);
        try {
            if (traceFile.getParent() != null) {
                Files.createDirectories(traceFile.getParent());
            }
        } catch (IOException e) {
            log.warn("cannot create trace dir: {}", e.getMessage());
        }
        loadFromDisk();
    }

    public OpsTrace newTrace(String instruction) {
        OpsTrace t = new OpsTrace();
        t.setTraceId(UUID.randomUUID().toString());
        t.setInstruction(instruction);
        t.setStartEpochMs(System.currentTimeMillis());
        t.setFinalStatus("RUNNING");
        traces.put(t.getTraceId(), t);
        order.add(t.getTraceId());
        evict();
        // 断点续跑：落一条 OPEN 行(含指令)，使重启后仍能重建该 trace 供 /api/ops/trace 查询。
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("traceId", t.getTraceId());
        open.put("ts", Instant.now().toString());
        open.put("stage", "OPEN");
        open.put("instruction", instruction);
        open.put("startEpochMs", t.getStartEpochMs());
        writeLine(open);
        return t;
    }

    public void appendStep(String traceId, AgentStep step) {
        AgentStep safeStep = sanitizeStep(step);
        OpsTrace t = traces.get(traceId);
        if (t != null) {
            t.getSteps().add(safeStep);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("traceId", traceId);
        line.put("ts", Instant.now().toString());
        line.put("stage", safeStep.stage());
        line.put("agent", safeStep.agentName());
        line.put("status", safeStep.status());
        line.put("elapsedMs", safeStep.elapsedMs());
        line.put("model", safeStep.model());
        line.put("confidence", safeStep.confidence());
        line.put("output", safeStep.output());
        writeLine(line);
    }

    public void complete(String traceId, String finalStatus) {
        OpsTrace t = traces.get(traceId);
        if (t != null) {
            t.setFinalStatus(finalStatus);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("traceId", traceId);
        line.put("ts", Instant.now().toString());
        line.put("stage", "DONE");
        line.put("finalStatus", finalStatus);
        writeLine(line);
    }

    /**
     * 按 traceId 查询：先查内存 LRU；未命中则回扫当前与归档 JSONL 文件重建
     * （重启/被 LRU 淘汰/已轮转归档的历史 trace 仍可追溯）。
     */
    public OpsTrace get(String traceId) {
        OpsTrace t = traces.get(traceId);
        return t != null ? t : scanFromDisk(traceId);
    }

    public List<OpsTrace> recent(int limit) {
        List<OpsTrace> out = new ArrayList<>();
        synchronized (order) {
            for (int i = order.size() - 1; i >= 0 && out.size() < limit; i--) {
                OpsTrace t = traces.get(order.get(i));
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /**
     * 落盘前先做字段级脱敏（对 Map 递归 sanitizeValue），再序列化为 JSON——
     * 而不是反过来对已序列化的整行 JSON 字符串跑正则。后者一旦秘密值后面
     * 紧跟 JSON 结构字符（无自然空白分隔，如 newTrace 的 instruction 本身
     * 就以秘密值结尾），"非空白贪婪匹配"会一路吃穿闭合引号/逗号/后续字段，
     * 产出损坏的 JSONL 行；字段级脱敏只在单个字符串值内部替换，不会跨到
     * JSON 语法上，从根上消除该风险。
     */
    private synchronized void writeLine(Map<String, Object> line) {
        try {
            rotateIfNeeded();
            Object safeLine = sanitizer == null ? line : sanitizer.sanitizeValue(line);
            String json = mapper.writeValueAsString(safeLine) + "\n";
            Files.write(traceFile, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("failed to write trace line: {}", e.getMessage());
        }
    }

    /** 活动文件超阈值时归档为 <base>-<epochMillis>.jsonl（审计不丢失，文件不无限增长）。 */
    private void rotateIfNeeded() {
        try {
            if (!Files.exists(traceFile) || Files.size(traceFile) < rotateBytes) {
                return;
            }
            // 同一毫秒内连续轮转时递增时间戳去重，保证归档名唯一且字典序仍即时间序
            long ts = System.currentTimeMillis();
            Path archive = traceFile.resolveSibling(baseName() + "-" + ts + ".jsonl");
            while (Files.exists(archive)) {
                archive = traceFile.resolveSibling(baseName() + "-" + (++ts) + ".jsonl");
            }
            try {
                Files.move(traceFile, archive, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(traceFile, archive);
            }
            log.info("溯源文件超过 {} bytes 已轮转归档：{}", rotateBytes, archive);
        } catch (IOException e) {
            log.warn("溯源文件轮转失败（继续追加原文件）：{}", e.getMessage());
        }
    }

    /** 活动文件名去掉 .jsonl 后缀作为归档前缀。 */
    private String baseName() {
        String name = traceFile.getFileName().toString();
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
    }

    /** 当前 + 归档溯源文件，按时间从旧到新排列（归档名内嵌 epochMillis，字典序即时间序）。 */
    private List<Path> allTraceFilesChronological() {
        List<Path> files = new ArrayList<>();
        Path dir = traceFile.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            String prefix = baseName() + "-";
            try (var stream = Files.list(dir)) {
                stream.filter(p -> {
                            String n = p.getFileName().toString();
                            return n.startsWith(prefix) && n.endsWith(".jsonl")
                                    && n.substring(prefix.length(), n.length() - ".jsonl".length())
                                       .chars().allMatch(Character::isDigit);
                        })
                        .sorted()
                        .forEach(files::add);
            } catch (IOException e) {
                log.warn("列举归档溯源文件失败：{}", e.getMessage());
            }
        }
        if (Files.exists(traceFile)) {
            files.add(traceFile);
        }
        return files;
    }

    /**
     * 兜底回扫：按 traceId 扫描当前与归档 JSONL，重建单条 trace 并放回内存 LRU。
     * 仅在内存未命中时触发（正常查询不付出扫描成本）；找不到返回 null。
     */
    private synchronized OpsTrace scanFromDisk(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        OpsTrace cached = traces.get(traceId);
        if (cached != null) {
            return cached; // 双检：等锁期间可能已被其他线程重建
        }
        OpsTrace t = null;
        for (Path f : allTraceFilesChronological()) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || !line.contains(traceId)) {
                        continue; // 廉价预过滤，仅对命中行做 JSON 解析
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = mapper.readValue(line, Map.class);
                        if (!traceId.equals(String.valueOf(m.get("traceId")))) {
                            continue;
                        }
                        String stage = String.valueOf(m.get("stage"));
                        if ("OPEN".equals(stage)) {
                            t = new OpsTrace();
                            t.setTraceId(traceId);
                            t.setInstruction(m.get("instruction") == null ? "" : String.valueOf(m.get("instruction")));
                            t.setStartEpochMs(m.get("startEpochMs") instanceof Number n ? n.longValue() : 0L);
                            t.setFinalStatus("RUNNING");
                        } else if ("DONE".equals(stage)) {
                            if (t != null) t.setFinalStatus(String.valueOf(m.get("finalStatus")));
                        } else if (t != null) {
                            t.getSteps().add(stepFromLine(m));
                        }
                    } catch (Exception badLine) {
                        // 单行损坏不影响其余重建
                    }
                }
            } catch (Exception e) {
                log.warn("回扫溯源文件 {} 失败：{}", f, e.getMessage());
            }
        }
        if (t != null) {
            traces.put(traceId, t);
            order.add(traceId);
            evict();
            log.info("溯源回扫命中：{} 已从磁盘重建（{} 步）", traceId, t.getSteps().size());
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private AgentStep sanitizeStep(AgentStep step) {
        if (sanitizer == null || step == null) {
            return step;
        }
        Object output = sanitizer.sanitizeValue(step.output());
        Object input = sanitizer.sanitizeValue(step.input());
        return new AgentStep(
                step.stage(),
                step.agentName(),
                input instanceof Map<?, ?> inputMap ? (Map<String, Object>) inputMap : step.input(),
                output instanceof Map<?, ?> outputMap ? (Map<String, Object>) outputMap : step.output(),
                step.model(),
                step.confidence(),
                step.elapsedMs(),
                step.tokenIn(),
                step.tokenOut(),
                step.status());
    }

    private void evict() {
        synchronized (order) {
            while (order.size() > MAX_IN_MEMORY) {
                String old = order.remove(0);
                traces.remove(old);
            }
        }
    }

    /**
     * 断点续跑：启动时从 JSONL 重建近期 trace，使服务重启后 /api/ops/trace/{id} 仍可查到
     * 重启前已完成的溯源记录（OPEN 行建 trace，step 行补步骤，DONE 行落最终状态）。
     * 损坏行跳过；只保留最近 MAX_IN_MEMORY 条。
     */
    private void loadFromDisk() {
        if (!Files.exists(traceFile)) {
            return;
        }
        try {
            // 大文件不预热重建（避免拖慢启动/测试）；轮转机制使活动文件正常不会达到该阈值。
            // JSONL 仍是完整 durable 审计：未预热的历史 trace 由 get() 的磁盘回扫按需重建。
            if (Files.size(traceFile) > rotateBytes) {
                log.info("溯源文件 {} 较大，跳过启动预热重建（历史 trace 仍可按需回扫）", traceFile);
                return;
            }
        } catch (IOException ignored) {
            // 无法取大小则照常尝试
        }
        int rebuilt = 0;
        try {
            for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = mapper.readValue(line, Map.class);
                    String traceId = String.valueOf(m.get("traceId"));
                    if (traceId == null || traceId.isBlank() || "null".equals(traceId)) continue;
                    String stage = String.valueOf(m.get("stage"));
                    if ("OPEN".equals(stage)) {
                        OpsTrace t = new OpsTrace();
                        t.setTraceId(traceId);
                        t.setInstruction(m.get("instruction") == null ? "" : String.valueOf(m.get("instruction")));
                        t.setStartEpochMs(m.get("startEpochMs") instanceof Number n ? n.longValue() : 0L);
                        t.setFinalStatus("RUNNING");
                        if (!traces.containsKey(traceId)) {
                            order.add(traceId);
                        }
                        traces.put(traceId, t);
                        rebuilt++;
                    } else if ("DONE".equals(stage)) {
                        OpsTrace t = traces.get(traceId);
                        if (t != null) t.setFinalStatus(String.valueOf(m.get("finalStatus")));
                    } else {
                        OpsTrace t = traces.get(traceId);
                        if (t != null) t.getSteps().add(stepFromLine(m));
                    }
                } catch (Exception badLine) {
                    // 单行损坏不影响其余重建
                }
            }
            evict();
            if (rebuilt > 0) {
                log.info("断点续跑：从 {} 重建 {} 条溯源记录", traceFile, rebuilt);
            }
        } catch (Exception e) {
            log.warn("溯源记录重建失败（从空索引启动）：{}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private AgentStep stepFromLine(Map<String, Object> m) {
        Map<String, Object> output = m.get("output") instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of();
        return new AgentStep(
                String.valueOf(m.getOrDefault("stage", "")),
                String.valueOf(m.getOrDefault("agent", "")),
                Map.of(),
                output,
                m.get("model") == null ? null : String.valueOf(m.get("model")),
                m.get("confidence") instanceof Number c ? c.doubleValue() : null,
                m.get("elapsedMs") instanceof Number e ? e.longValue() : null,
                null, null,
                String.valueOf(m.getOrDefault("status", "ok")));
    }
}
