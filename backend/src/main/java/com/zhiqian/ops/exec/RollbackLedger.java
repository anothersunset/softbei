package com.zhiqian.ops.exec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作账本登记：按 traceId 保存可一键回滚的补偿计划，供 /api/ops/rollback/{traceId} 回放。
 * 断点续跑：配置 state-dir 后账本同步 JSONL 落盘，进程重启后自动恢复（重启不丢账）；
 * 未配置（无参构造/空 state-dir）时保持纯内存，测试与评测行为不受影响。
 */
@Component
public class RollbackLedger {
    private static final Logger log = LoggerFactory.getLogger(RollbackLedger.class);
    private static final int MAX_IN_MEMORY = 200;
    private static final long TTL_MS = 24 * 60 * 60 * 1000L;
    private final Map<String, List<Map<String, Object>>> store = new ConcurrentHashMap<>();
    private final Map<String, Long> createdAt = new ConcurrentHashMap<>();
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());
    private final Path persistFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 纯内存账本（测试/评测路径）。 */
    public RollbackLedger() {
        this.persistFile = null;
    }

    /** 指定落盘文件的持久化账本（重启自动恢复）。 */
    public RollbackLedger(Path persistFile) {
        this.persistFile = persistFile;
        loadFromDisk();
    }

    @Autowired
    public RollbackLedger(ExecProperties props) {
        String dir = props == null ? null : props.getStateDir();
        this.persistFile = dir == null || dir.isBlank() ? null : Path.of(dir).resolve("rollback-ledger.jsonl");
        loadFromDisk();
    }

    public void record(String traceId, List<Map<String, Object>> ledger) {
        if (traceId != null && ledger != null && !ledger.isEmpty()) {
            evict();
            store.put(traceId, ledger);
            createdAt.put(traceId, System.currentTimeMillis());
            order.remove(traceId);
            order.add(traceId);
            evict();
            persist();
        }
    }

    public List<Map<String, Object>> get(String traceId) {
        evict();
        return store.getOrDefault(traceId, List.of());
    }

    public boolean has(String traceId) {
        evict();
        return store.containsKey(traceId);
    }

    private void evict() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        synchronized (order) {
            order.removeIf(id -> {
                Long ts = createdAt.get(id);
                boolean expired = ts == null || ts < cutoff;
                if (expired) {
                    store.remove(id);
                    createdAt.remove(id);
                }
                return expired;
            });
            while (order.size() > MAX_IN_MEMORY) {
                String old = order.remove(0);
                store.remove(old);
                createdAt.remove(old);
            }
        }
    }

    /** 全量重写落盘（条目≤200，代价可忽略；追加+墓碑会引入合并复杂度，不值得）。 */
    private synchronized void persist() {
        if (persistFile == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (order) {
                for (String id : order) {
                    List<Map<String, Object>> ledger = store.get(id);
                    Long ts = createdAt.get(id);
                    if (ledger == null || ts == null) continue;
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("traceId", id);
                    line.put("createdAt", ts);
                    line.put("ledger", ledger);
                    sb.append(mapper.writeValueAsString(line)).append('\n');
                }
            }
            if (persistFile.getParent() != null) {
                Files.createDirectories(persistFile.getParent());
            }
            Files.writeString(persistFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("回滚账本落盘失败（不影响内存账本）：{}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        if (persistFile == null || !Files.exists(persistFile)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TTL_MS;
        int loaded = 0;
        try {
            for (String line : Files.readAllLines(persistFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> entry = mapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                    String traceId = String.valueOf(entry.get("traceId"));
                    long ts = entry.get("createdAt") instanceof Number n ? n.longValue() : 0L;
                    Object ledgerObj = entry.get("ledger");
                    if (traceId.isBlank() || ts < cutoff || !(ledgerObj instanceof List<?>)) continue;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> ledger = (List<Map<String, Object>>) ledgerObj;
                    store.put(traceId, ledger);
                    createdAt.put(traceId, ts);
                    order.remove(traceId);
                    order.add(traceId);
                    loaded++;
                } catch (Exception badLine) {
                    log.warn("回滚账本损坏行已跳过：{}", badLine.getMessage());
                }
            }
            if (loaded > 0) {
                log.info("断点续跑：从 {} 恢复 {} 条回滚账本", persistFile, loaded);
            }
        } catch (Exception e) {
            log.warn("回滚账本恢复失败（从空账本启动）：{}", e.getMessage());
        }
    }
}
