package com.zhiqian.ops.agent;

import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 运行器（忠实沿用原项目「逐节点迭代 + onStep 回调记录」模式）。
 * 为每个节点计时、构造 AgentStep、并在 _halt 为 true 时短路。
 */
@Component
public class AgentRunner {
    private final SensitiveDataSanitizer sanitizer;

    public AgentRunner() {
        this(null);
    }

    @Autowired
    public AgentRunner(SensitiveDataSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public List<AgentStep> run(List<AgentNode> nodes, AgentContext ctx, Consumer<AgentStep> onStep) {
        List<AgentStep> steps = new ArrayList<>();
        for (AgentNode node : nodes) {
            long start = System.currentTimeMillis();
            Map<String, Object> out;
            String status = "ok";
            try {
                out = node.run(ctx);
                if (out == null) {
                    out = new HashMap<>();
                }
            } catch (Exception e) {
                out = new HashMap<>();
                out.put("error", e.getMessage());
                status = "error";
            }
            long elapsed = System.currentTimeMillis() - start;
            String finalStatus = out.containsKey("error") ? "error" : str(out.getOrDefault("_status", status));
            AgentStep step = new AgentStep(
                    node.stage(),
                    node.agentName(),
                    new HashMap<>(),
                    sanitizeMap(out),
                    str(out.get("_model")),
                    dbl(out.get("_confidence")),
                    elapsed,
                    intg(out.get("_tokenIn")),
                    intg(out.get("_tokenOut")),
                    finalStatus
            );
            steps.add(step);
            if (onStep != null) {
                onStep.accept(step);
            }
            if (Boolean.TRUE.equals(out.get("_halt"))) {
                break;
            }
        }
        return steps;
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(Map<String, Object> out) {
        if (sanitizer == null || out == null) {
            return out;
        }
        Object sanitized = sanitizer.sanitizeValue(out);
        if (sanitized instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return out;
    }

    private Double dbl(Object o) {
        if (o instanceof Number n) { return n.doubleValue(); }
        if (o instanceof String s) { try { return Double.parseDouble(s); } catch (Exception ignored) {} }
        return null;
    }

    private Integer intg(Object o) {
        if (o instanceof Number n) { return n.intValue(); }
        if (o instanceof String s) { try { return Integer.parseInt(s); } catch (Exception ignored) {} }
        return null;
    }
}
