package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络感知工具（只读）：监听端口、连接统计。
 * 支持参数化：可选 port 只看某个端口的监听与连接情况（如「8080 被谁占用」），
 * 端口过滤经 ss 原生过滤表达式实现，argv 数组直传不经 shell。
 */
@Component
public class NetworkSenseTool implements AgentTool {
    private final LeastPrivilegeExecutor executor;

    public NetworkSenseTool(LeastPrivilegeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() { return "network_sense"; }

    @Override
    public String description() {
        return "采集网络信息：监听中的端口及进程(ss -tnlp)、各状态连接统计(ss -s)；"
                + "可选 port 只聚焦某个端口（监听方 + 已建立连接）";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("type", "integer");
        port.put("description", "只聚焦该端口：返回其监听进程与已建立连接；留空返回全量监听端口与统计");
        port.put("minimum", 1);
        port.put("maximum", 65535);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("port", port);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        int port = AgentTool.intArg(input, "port", 0, 0, 65535);
        Map<String, Object> r = new LinkedHashMap<>();
        if (port > 0) {
            r.put("port", port);
            r.put("listening", executor.runReadOnly(
                    List.of("ss", "-tnlp", "sport", "=", ":" + port)).stdout());
            r.put("established", executor.runReadOnly(
                    List.of("ss", "-tnp", "state", "established", "sport", "=", ":" + port)).stdout());
            return r;
        }
        r.put("listening_ports", executor.runReadOnly(List.of("ss", "-tnlp")).stdout());
        r.put("socket_summary", executor.runReadOnly(List.of("ss", "-s")).stdout());
        return r;
    }
}
