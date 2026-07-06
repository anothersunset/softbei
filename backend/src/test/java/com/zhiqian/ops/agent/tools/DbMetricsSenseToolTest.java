package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 数据库/监控感知工具：结构化输出与优雅降级（不依赖目标机真实部署数据库或 node_exporter）。 */
class DbMetricsSenseToolTest {

    @TempDir
    Path tmp;

    private LeastPrivilegeExecutor executor() {
        ExecProperties props = new ExecProperties();
        props.setWorkingDir(tmp.toString());
        props.setOutputAuditDir(tmp.resolve("exec-output").toString());
        return new LeastPrivilegeExecutor(props, new CircuitBreaker());
    }

    @Test
    void db_sense_auto_probes_all_engines() {
        DbSenseTool tool = new DbSenseTool(executor());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of());
        assertEquals("auto", r.get("engine"));
        assertTrue(r.containsKey("mysql"));
        assertTrue(r.containsKey("postgresql"));
        assertTrue(r.containsKey("redis"));
        Map<?, ?> mysql = (Map<?, ?>) r.get("mysql");
        assertEquals(3306, mysql.get("listenPort"));
        assertTrue(mysql.containsKey("processUp"));
        assertTrue(mysql.containsKey("establishedConnections"));
    }

    @Test
    void db_sense_single_engine_only_probes_that_engine() {
        DbSenseTool tool = new DbSenseTool(executor());
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L), Map.of("engine", "redis"));
        assertTrue(r.containsKey("redis"));
        assertFalse(r.containsKey("mysql"));
    }

    @Test
    void db_sense_schema_uses_enum_whitelist() {
        Map<String, Object> schema = new DbSenseTool(executor()).inputSchema();
        String rendered = String.valueOf(schema);
        assertTrue(rendered.contains("enum"));
        assertTrue(rendered.contains("auto"));
    }

    @Test
    void metrics_sense_degrades_gracefully_when_endpoint_unreachable() {
        MetricsSenseTool tool = new MetricsSenseTool();
        // 选择一个几乎必然无监听的端口，验证优雅降级而非抛异常
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L),
                Map.of("source", "node-exporter", "port", 59999));
        assertEquals(false, r.get("available"));
        assertNotNull(r.get("degraded"));
        assertTrue(String.valueOf(r.get("endpoint")).startsWith("http://127.0.0.1:59999"));
    }

    @Test
    void metrics_sense_endpoint_is_loopback_only() {
        // 反 SSRF：无论入参如何，endpoint 恒为 127.0.0.1 + 白名单路径
        MetricsSenseTool tool = new MetricsSenseTool();
        Map<String, Object> r = tool.run(new AgentContext(0L, 0L),
                Map.of("source", "actuator", "port", 59998));
        assertEquals("http://127.0.0.1:59998/actuator/prometheus", r.get("endpoint"));
    }
}
