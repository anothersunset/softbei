package com.zhiqian.ops.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主备模型自动切换：失败降级、Mock 兜底、冷却回切。 */
class FailoverLlmClientTest {

    /** 可编程桩：按开关抛异常或回显。 */
    private static final class StubClient implements LlmClient {
        private final String name;
        volatile boolean failing;
        final AtomicInteger calls = new AtomicInteger();

        StubClient(String name, boolean failing) {
            this.name = name;
            this.failing = failing;
        }

        @Override
        public boolean isReal() { return true; }

        @Override
        public String providerName() { return name; }

        @Override
        public String chat(String prompt) {
            calls.incrementAndGet();
            if (failing) {
                throw new RuntimeException(name + " unavailable");
            }
            return name + ":" + prompt;
        }
    }

    @Test
    void fails_over_to_secondary_then_mock_and_reports_active_provider() {
        StubClient primary = new StubClient("deepseek", true);
        StubClient secondary = new StubClient("qwen", true);
        FailoverLlmClient client = new FailoverLlmClient(
                List.of(primary, secondary, new MockLlmClient()), 60_000);

        // 主备全故障 → Mock 兜底，服务不中断
        String out = client.chat("帮我看看磁盘");
        assertTrue(out.contains("{"), "Mock 应返回 JSON 计划");
        assertEquals(1, primary.calls.get());
        assertEquals(1, secondary.calls.get());
        assertTrue(client.providerName().startsWith("mock"));
        assertTrue(client.providerName().contains("failover"));
        // 降级到 Mock 后 isReal=false，下游 ReAct/交叉校验自动降级
        assertFalse(client.isReal());

        // 已降级：后续调用直达当前生效级，不再反复敲主模型
        client.chat("再看一次");
        assertEquals(1, primary.calls.get());
    }

    @Test
    void uses_secondary_when_only_primary_fails() {
        StubClient primary = new StubClient("deepseek", true);
        StubClient secondary = new StubClient("qwen", false);
        FailoverLlmClient client = new FailoverLlmClient(
                List.of(primary, secondary, new MockLlmClient()), 60_000);

        assertEquals("qwen:hello", client.chat("hello"));
        assertTrue(client.isReal());
        assertEquals("qwen(failover#1)", client.providerName());
    }

    @Test
    void recovers_to_primary_after_cooldown() throws Exception {
        StubClient primary = new StubClient("deepseek", true);
        StubClient secondary = new StubClient("qwen", false);
        FailoverLlmClient client = new FailoverLlmClient(
                List.of(primary, secondary, new MockLlmClient()), 50);

        assertEquals("qwen:a", client.chat("a"));
        primary.failing = false;
        Thread.sleep(80); // 越过冷却期
        assertEquals("deepseek:b", client.chat("b"));
        assertEquals("deepseek", client.providerName());
    }

    @Test
    void healthy_primary_is_used_directly() {
        StubClient primary = new StubClient("deepseek", false);
        FailoverLlmClient client = new FailoverLlmClient(
                List.of(primary, new MockLlmClient()), 60_000);

        assertEquals("deepseek:ping", client.chat("ping"));
        assertEquals("deepseek", client.providerName());
        assertTrue(client.isReal());
    }
}
