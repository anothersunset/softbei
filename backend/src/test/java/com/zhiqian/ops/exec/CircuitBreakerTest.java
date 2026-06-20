package com.zhiqian.ops.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CircuitBreaker 单元测试：覆盖 CLOSED/OPEN/HALF_OPEN 状态机全路径。
 */
class CircuitBreakerTest {

    @Nested
    @DisplayName("CircuitBreaker 状态机")
    class StateMachine {

        @Test
        void initial_state_closed() {
            CircuitBreaker cb = new CircuitBreaker(3, 1000);
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
            assertEquals(0, cb.consecutiveFailures());
        }

        @Test
        void default_constructor() {
            CircuitBreaker cb = new CircuitBreaker();
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        }

        @Test
        void allowExecution_closed_returns_true() {
            CircuitBreaker cb = new CircuitBreaker(3, 1000);
            assertTrue(cb.allowExecution());
        }

        @Test
        void recordSuccess_resets_failures() {
            CircuitBreaker cb = new CircuitBreaker(3, 1000);
            cb.recordFailure();
            cb.recordFailure();
            assertEquals(2, cb.consecutiveFailures());
            cb.recordSuccess();
            assertEquals(0, cb.consecutiveFailures());
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        }

        @Test
        void recordFailure_below_threshold_stays_closed() {
            CircuitBreaker cb = new CircuitBreaker(3, 1000);
            cb.recordFailure();
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
            assertEquals(2, cb.consecutiveFailures());
        }

        @Test
        void recordFailure_at_threshold_opens() {
            CircuitBreaker cb = new CircuitBreaker(3, 1000);
            cb.recordFailure();
            cb.recordFailure();
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
        }

        @Test
        void allowExecution_open_during_cooldown_returns_false() {
            CircuitBreaker cb = new CircuitBreaker(1, 60000);
            cb.recordFailure(); // triggers OPEN
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
            assertFalse(cb.allowExecution());
        }

        @Test
        void allowExecution_open_after_cooldown_transitions_half_open() {
            CircuitBreaker cb = new CircuitBreaker(1, 10); // 10ms cooldown
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
            
            // Wait for cooldown
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            
            assertTrue(cb.allowExecution());
            assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        }

        @Test
        void recordFailure_half_open_reopens() {
            CircuitBreaker cb = new CircuitBreaker(1, 10);
            cb.recordFailure(); // OPEN
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            cb.allowExecution(); // HALF_OPEN
            assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
            
            cb.recordFailure(); // Should re-open
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
        }

        @Test
        void recordSuccess_half_open_closes() {
            CircuitBreaker cb = new CircuitBreaker(1, 10);
            cb.recordFailure(); // OPEN
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            cb.allowExecution(); // HALF_OPEN
            
            cb.recordSuccess(); // Should close
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
            assertEquals(0, cb.consecutiveFailures());
        }

        @Test
        void remainingCooldown_closed_returns_zero() {
            CircuitBreaker cb = new CircuitBreaker(3, 1000);
            assertEquals(0L, cb.remainingCooldownMillis());
        }

        @Test
        void remainingCooldown_open_returns_positive() {
            CircuitBreaker cb = new CircuitBreaker(1, 60000);
            cb.recordFailure();
            long remaining = cb.remainingCooldownMillis();
            assertTrue(remaining > 0);
            assertTrue(remaining <= 60000);
        }

        @Test
        void remainingCooldown_after_cooldown_returns_zero() {
            CircuitBreaker cb = new CircuitBreaker(1, 10);
            cb.recordFailure();
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            assertEquals(0L, cb.remainingCooldownMillis());
        }
    }
}
