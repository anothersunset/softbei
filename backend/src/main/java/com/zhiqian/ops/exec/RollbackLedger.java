package com.zhiqian.ops.exec;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作账本登记：按 traceId 保存可一键回滚的补偿计划，供 /api/ops/rollback/{traceId} 回放。
 * 内存级实现（演示/初赛足够）；生产可替换为持久化存储以支持跨重启回滚。
 */
@Component
public class RollbackLedger {
    private static final int MAX_IN_MEMORY = 200;
    private static final long TTL_MS = 24 * 60 * 60 * 1000L;
    private final Map<String, List<Map<String, Object>>> store = new ConcurrentHashMap<>();
    private final Map<String, Long> createdAt = new ConcurrentHashMap<>();
    private final List<String> order = Collections.synchronizedList(new ArrayList<>());

    public void record(String traceId, List<Map<String, Object>> ledger) {
        if (traceId != null && ledger != null && !ledger.isEmpty()) {
            evict();
            store.put(traceId, ledger);
            createdAt.put(traceId, System.currentTimeMillis());
            order.remove(traceId);
            order.add(traceId);
            evict();
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
}
