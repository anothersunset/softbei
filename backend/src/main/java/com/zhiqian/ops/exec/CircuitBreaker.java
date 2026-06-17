package com.zhiqian.ops.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 变更类命令执行熔断器（兜底机制）。
 * 当变更类真实执行连续失败达到阈值时进入 OPEN，在冷却期内短路后续高危执行，
 * 避免单点故障被 Agent 反复重试放大为级联事故；冷却结束转 HALF_OPEN 放行一次试探，
 * 成功恢复 CLOSED，失败则重新熔断。
 * 仅作用于变更类真实执行：dry-run 与只读/感知命令不受影响，保证评测可复现。
 */
@Component
public class CircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0L);
    private volatile State state = State.CLOSED;

    public CircuitBreaker() {
        this(3, 30_000L);
    }

    public CircuitBreaker(int failureThreshold, long cooldownMillis) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    /** 是否允许放行一次变更类执行。OPEN 且仍在冷却期内则拒绝。 */
    public synchronized boolean allowExecution() {
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= cooldownMillis) {
                state = State.HALF_OPEN;
                log.warn("熔断器进入 HALF_OPEN：放行一次试探执行");
                return true;
            }
            return false;
        }
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures.set(0);
        if (state != State.CLOSED) {
            log.info("熔断器恢复 CLOSED");
        }
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        int n = consecutiveFailures.incrementAndGet();
        if (state == State.HALF_OPEN || n >= failureThreshold) {
            state = State.OPEN;
            openedAt.set(System.currentTimeMillis());
            log.warn("熔断器触发 OPEN：连续失败 {} 次，冷却 {}ms 内暂停变更类执行", n, cooldownMillis);
        }
    }

    public State state() { return state; }

    public int consecutiveFailures() { return consecutiveFailures.get(); }

    public long remainingCooldownMillis() {
        if (state != State.OPEN) return 0L;
        return Math.max(0L, cooldownMillis - (System.currentTimeMillis() - openedAt.get()));
    }
}
