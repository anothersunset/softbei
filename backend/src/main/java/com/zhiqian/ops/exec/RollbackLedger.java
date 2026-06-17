package com.zhiqian.ops.exec;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作账本登记：按 traceId 保存可一键回滚的补偿计划，供 /api/ops/rollback/{traceId} 回放。
 * 内存级实现（演示/初赛足够）；生产可替换为持久化存储以支持跨重启回滚。
 */
@Component
public class RollbackLedger {
    private final Map<String, List<Map<String, Object>>> store = new ConcurrentHashMap<>();

    public void record(String traceId, List<Map<String, Object>> ledger) {
        if (traceId != null && ledger != null && !ledger.isEmpty()) {
            store.put(traceId, ledger);
        }
    }

    public List<Map<String, Object>> get(String traceId) {
        return store.getOrDefault(traceId, List.of());
    }

    public boolean has(String traceId) {
        return store.containsKey(traceId);
    }
}
