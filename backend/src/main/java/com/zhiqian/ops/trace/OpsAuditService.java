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
 */
@Service
public class OpsAuditService {
    private static final Logger log = LoggerFactory.getLogger(OpsAuditService.class);
    private static final int MAX_IN_MEMORY = 200;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path traceFile;
    private final SensitiveDataSanitizer sanitizer;
    private final Map<String, OpsTrace> traces = new ConcurrentHashMap<>();
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());

    public OpsAuditService(@Value("${ops.trace.file:logs/ops-trace.jsonl}") String file) {
        this(file, null);
    }

    @Autowired
    public OpsAuditService(@Value("${ops.trace.file:logs/ops-trace.jsonl}") String file,
                           SensitiveDataSanitizer sanitizer) {
        this.traceFile = Paths.get(file);
        this.sanitizer = sanitizer;
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

    public OpsTrace get(String traceId) {
        return traces.get(traceId);
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

    private synchronized void writeLine(Map<String, Object> line) {
        try {
            String json = mapper.writeValueAsString(line);
            if (sanitizer != null) {
                json = sanitizer.sanitize(json);
            }
            json = json + "\n";
            Files.write(traceFile, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("failed to write trace line: {}", e.getMessage());
        }
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
            // 大文件不预热重建（避免拖慢启动/测试）；JSONL 仍是完整 durable 审计，仅查询不预热。
            if (Files.size(traceFile) > 32L * 1024 * 1024) {
                log.info("溯源文件 {} 较大，跳过启动预热重建（审计完整性不受影响）", traceFile);
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
