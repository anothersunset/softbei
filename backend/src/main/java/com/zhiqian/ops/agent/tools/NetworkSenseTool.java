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
    public String description() { return "采集网络信息：监听中的端口及进程(ss -tnlp)、各状态连接统计(ss -s)"; }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("listening_ports", executor.runReadOnly(List.of("ss", "-tnlp")).stdout());
        r.put("socket_summary", executor.runReadOnly(List.of("ss", "-s")).stdout());
        return r;
    }
}
