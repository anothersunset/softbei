package com.zhiqian.ops.llm;

import com.zhiqian.ops.mcp.McpToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 主备模型自动切换客户端（对齐官方"执行确定性：失败自动切换/主备模型自动切换"要求）。
 *
 * 委托链按优先级排列（如 DeepSeek 主 → Qwen 备 → Mock 兜底）：
 *  - 调用失败自动降级到下一级并记录切换事件，最后一级为本地 Mock，保证服务永不因模型不可用而中断；
 *  - 冷却期（默认 2 分钟）过后自动回切主模型探活，主模型恢复后无感回归；
 *  - isReal()/supportsTools() 始终反映【当前生效】的委托，下游（ReAct / 意图交叉校验）随之自动降级。
 */
public class FailoverLlmClient implements LlmClient {
    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final List<LlmClient> chain;
    private final long cooldownMs;
    /** 当前生效的委托下标；0 为主模型。 */
    private volatile int active = 0;
    /** 最近一次降级时刻，用于冷却回切。 */
    private volatile long degradedAtMs = 0;

    public FailoverLlmClient(List<LlmClient> chain, long cooldownMs) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("failover chain 不能为空");
        }
        this.chain = List.copyOf(chain);
        this.cooldownMs = Math.max(1, cooldownMs);
    }

    @Override
    public boolean isReal() { return current().isReal(); }

    @Override
    public boolean supportsTools() { return current().supportsTools(); }

    @Override
    public String providerName() {
        int idx = activeIndex();
        String name = chain.get(idx).providerName();
        return idx == 0 ? name : name + "(failover#" + idx + ")";
    }

    @Override
    public String chat(String prompt) {
        return invoke(c -> c.chat(prompt), "chat");
    }

    @Override
    public ToolChatResult chatWithTools(List<ChatMessage> messages, List<McpToolSpec> tools) {
        return invoke(c -> c.chatWithTools(messages, tools), "chatWithTools");
    }

    /** 沿委托链从当前生效级开始逐级尝试；失败降级并记录，末级（Mock）不再吞异常。 */
    private <T> T invoke(Call<T> call, String op) {
        int start = activeIndex();
        RuntimeException last = null;
        for (int i = start; i < chain.size(); i++) {
            LlmClient delegate = chain.get(i);
            try {
                T result = call.apply(delegate);
                if (i != active) {
                    active = i;
                    degradedAtMs = System.currentTimeMillis();
                    log.warn("[llm-failover] {} 已切换到备用模型 provider={}（第 {} 级）",
                            op, delegate.providerName(), i);
                }
                return result;
            } catch (RuntimeException e) {
                last = e;
                log.warn("[llm-failover] provider={} {} 调用失败：{}，尝试下一级",
                        delegate.providerName(), op, e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("llm failover chain 无可用委托");
    }

    /** 冷却期过后回切主模型探活；主模型仍故障会在下次调用时再次自然降级。 */
    private int activeIndex() {
        if (active > 0 && System.currentTimeMillis() - degradedAtMs > cooldownMs) {
            log.info("[llm-failover] 冷却期结束，回切主模型探活");
            active = 0;
        }
        return active;
    }

    private LlmClient current() {
        return chain.get(activeIndex());
    }

    @FunctionalInterface
    private interface Call<T> {
        T apply(LlmClient client);
    }
}
