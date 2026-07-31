package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.guard.CrossTurnProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级跨轮防护状态的全局内存表：按 sessionId 隔离，惰性 TTL 驱逐（每次 getOrCreate 顺带清理
 * 不活跃会话，无需额外定时任务，与 OpsPipeline.evictExpiredPlans() 的写法一致）。纯内存不落盘。
 */
@Component
public class SessionStore {
    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final CrossTurnProperties props;

    public SessionStore(CrossTurnProperties props) {
        this.props = props;
    }

    /**
     * 未携带 sessionId（评测语料、直接调用 API 等场景）视为一次性独立请求：
     * 返回一个不登记入表的临时会话，天然不与任何其他请求共享跨轮检测状态，
     * 保证评测确定性不受本功能影响（呼应设计文档「评测确定性保证」）。
     */
    public SessionContext getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SessionContext(null, props.getHistoryWindowTurns(), props.getEvidenceWindowTurns());
        }
        evictExpired();
        String key = sessionId.trim();
        SessionContext ctx = sessions.computeIfAbsent(key,
                k -> new SessionContext(k, props.getHistoryWindowTurns(), props.getEvidenceWindowTurns()));
        ctx.touch();
        return ctx;
    }

    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - props.getSessionTtlMinutes() * 60_000L;
        sessions.entrySet().removeIf(e -> e.getValue().lastActiveMs() < cutoff);
    }
}
