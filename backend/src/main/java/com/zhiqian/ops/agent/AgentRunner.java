package com.zhiqian.ops.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 顺序执行一组 AgentNode，逐节点计时并生成 AgentStep 回调。
 * 沿用原 智迁云枢 AgentRunner 的「run(graph, ctx, onStep)」模式：
 * 逐步记录 model/confidence/tokens/elapsed/status，作为推理链路溯源骨架。
 */
@Component
public class AgentRunner {

    public List<AgentStep> run(List<AgentNode> nodes, AgentContext ctx, Consumer<AgentStep> onStep) {
        List<AgentStep> steps = new ArrayList<>();
        for (AgentNode node : nodes) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> out;
            String status;
            try {
                out = node.run(ctx);
                if (out == null) {
                    out = new HashMap<>();
                }
                status = String.valueOf(out.getOrDefault("_status", "success"));
            } catch (Exception e) {
                out = new HashMap<>();
                out.put("error", String.valueOf(e.getMessage()));
                status = "error";
            }
            long elapsed = System.currentTimeMillis() - t0;
            AgentStep step = new AgentStep(
                    node.stage(),
                    node.agentName(),
                    new HashMap<>(ctx.state()),
                    out,
                    asStr(out.get("_model")),
                    asDouble(out.get("_confidence")),
                    elapsed,
                    asInt(out.get("_tokenIn")),
                    asInt(out.get("_tokenOut")),
                    status
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

    private String asStr(Object o) { return o == null ? null : o.toString(); }
    private Double asDouble(Object o) { return (o instanceof Number n) ? n.doubleValue() : null; }
    private Integer asInt(Object o) { return (o instanceof Number n) ? n.intValue() : null; }
}
