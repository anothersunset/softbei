package com.zhiqian.ops.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiqian.ops.agent.AgentStep;
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
    private final Map<String, OpsTrace> traces = new ConcurrentHashMap<>();
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());

    public OpsAuditService(@Value("${ops.trace.file:logs/ops-trace.jsonl}") String file) {
        this.traceFile = Paths.get(file);
        try {
            if (traceFile.getParent() != null) {
                Files.createDirectories(traceFile.getParent());
            }
        } catch (IOException e) {
            log.warn("cannot create trace dir: {}", e.getMessage());
        }
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
        return t;
    }

    public void appendStep(String traceId, AgentStep step) {
        OpsTrace t = traces.get(traceId);
        if (t != null) {
            t.getSteps().add(step);
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("traceId", traceId);
        line.put("ts", Instant.now().toString());
        line.put("stage", step.stage());
        line.put("agent", step.agentName());
        line.put("status", step.status());
        line.put("elapsedMs", step.elapsedMs());
        line.put("model", step.model());
        line.put("confidence", step.confidence());
        line.put("output", step.output());
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
            String json = mapper.writeValueAsString(line) + "\n";
            Files.write(traceFile, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("failed to write trace line: {}", e.getMessage());
        }
    }

    private void evict() {
        synchronized (order) {
            while (order.size() > MAX_IN_MEMORY) {
                String old = order.remove(0);
                traces.remove(old);
            }
        }
    }
}
