package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控系统感知工具（只读）：对齐赛题「支持监控系统集成」要求。
 * 抓取 Prometheus 文本格式指标：node_exporter（主机级监控）或本服务 Actuator 自监控端点。
 * 反 SSRF 设计：目标 host 固定 127.0.0.1、路径由 source 枚举白名单决定，仅 port 可调（1~65535 夹紧），
 * 不接受任意 URL；监控端点不可达时优雅降级并给出接入指引。
 */
@Component
public class MetricsSenseTool implements AgentTool {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public String name() { return "metrics_sense"; }

    @Override
    public String description() {
        return "采集监控系统指标（Prometheus 文本格式）：source=node-exporter 抓取主机监控(默认 127.0.0.1:9100/metrics)，"
                + "source=actuator 抓取本服务自监控(默认 127.0.0.1:8080/actuator/prometheus)；"
                + "可选 filter 按指标名前缀过滤、lines 限制返回行数；端点不可达时优雅降级";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "string");
        source.put("description", "指标来源：node-exporter=主机级监控（需目标机部署 node_exporter），actuator=本服务自监控端点");
        source.put("enum", List.of("node-exporter", "actuator"));
        source.put("default", "node-exporter");
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("type", "integer");
        port.put("description", "监控端点端口；缺省 node-exporter=9100、actuator=8080");
        port.put("minimum", 1);
        port.put("maximum", 65535);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("type", "string");
        filter.put("description", "指标名前缀过滤，如 node_load、node_memory、jvm_memory；留空返回全部（受 lines 限制）");
        Map<String, Object> lines = new LinkedHashMap<>();
        lines.put("type", "integer");
        lines.put("description", "最多返回的指标行数（注释行不计）");
        lines.put("minimum", 1);
        lines.put("maximum", 500);
        lines.put("default", 50);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("source", source);
        props.put("port", port);
        props.put("filter", filter);
        props.put("lines", lines);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String source = AgentTool.safeStrArg(input, "source", "node-exporter");
        boolean actuator = "actuator".equals(source);
        int defaultPort = actuator ? 8080 : 9100;
        int port = AgentTool.intArg(input, "port", defaultPort, 1, 65535);
        String path = actuator ? "/actuator/prometheus" : "/metrics";
        String filter = AgentTool.safeStrArg(input, "filter", null);
        int maxLines = AgentTool.intArg(input, "lines", 50, 1, 500);
        // 反 SSRF：host 固定回环地址，路径由枚举决定，不拼接任何自由输入
        String url = "http://127.0.0.1:" + port + path;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("source", actuator ? "actuator" : "node-exporter");
        r.put("endpoint", url);
        if (filter != null) {
            r.put("filter", filter);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                r.put("available", false);
                r.put("degraded", "监控端点返回 HTTP " + resp.statusCode() + "，请确认端点已启用");
                return r;
            }
            List<String> kept = resp.body().lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .filter(line -> filter == null || line.startsWith(filter))
                    .limit(maxLines)
                    .toList();
            r.put("available", true);
            r.put("metricLines", kept.size());
            r.put("metrics", String.join("\n", kept));
        } catch (Exception e) {
            r.put("available", false);
            r.put("degraded", "监控端点不可达（" + e.getClass().getSimpleName() + "），"
                    + (actuator ? "请确认服务已启动且 management 端点已暴露"
                                : "请在目标机部署 node_exporter 或指定正确端口"));
        }
        return r;
    }
}
