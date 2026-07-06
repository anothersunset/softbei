package com.zhiqian.ops.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 工具抽象（沿用原项目接口形状）。
 * 每个运维动作都被封装为一个 Tool，通过 MCP 插件化暴露。
 */
public interface AgentTool {
    /** 工具唯一名称（也是 MCP tool name）。 */
    String name();

    /** 工具用途描述（供模型与 MCP 客户端理解）。 */
    String description();

    /** 执行工具，返回结构化结果。 */
    Map<String, Object> run(AgentContext ctx, Map<String, Object> input);

    /**
     * MCP inputSchema（JSON Schema）：声明工具入参，供模型 function calling 与 MCP 客户端理解。
     * 默认无入参（只读感知/巡检）。有参工具覆写此方法给出 properties。
     */
    default Map<String, Object> inputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * MCP ToolAnnotations：由工具自身声明行为提示。
     * 默认为只读感知/巡检工具（readOnlyHint=true）；变更类工具经 {@link MutatingTool} 覆写。
     */
    default Map<String, Object> annotations() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", name());
        a.put("readOnlyHint", true);
        a.put("destructiveHint", false);
        a.put("idempotentHint", true);
        a.put("openWorldHint", false);
        return a;
    }

    /** 安全读取整型入参并夹紧到 [min, max]；缺省/非法时取 def。 */
    static int intArg(Map<String, Object> input, String key, int def, int min, int max) {
        if (input == null) return def;
        Object v = input.get(key);
        int val = def;
        if (v instanceof Number n) {
            val = n.intValue();
        } else if (v instanceof String s && !s.isBlank()) {
            try { val = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { val = def; }
        }
        return Math.max(min, Math.min(max, val));
    }

    /** 安全读取布尔入参：接受 Boolean 或 "true"/"false" 字符串；缺省/非法时取 def。 */
    static boolean boolArg(Map<String, Object> input, String key, boolean def) {
        if (input == null) return def;
        Object v = input.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return def;
    }

    /**
     * 安全读取字符串入参：仅保留白名单字符 [A-Za-z0-9._@:/-]，其余剔除，防止污染 argv。
     * 缺省或清洗后为空时返回 def。
     */
    static String safeStrArg(Map<String, Object> input, String key, String def) {
        if (input == null) return def;
        Object v = input.get(key);
        if (!(v instanceof String s) || s.isBlank()) return def;
        String cleaned = s.trim().replaceAll("[^A-Za-z0-9._@:/-]", "");
        return cleaned.isBlank() ? def : cleaned;
    }
}
