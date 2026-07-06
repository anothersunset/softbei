package com.zhiqian.ops.agent.tools;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库感知工具（只读）：对齐赛题「支持数据库集成」要求。
 * 不引入任何数据库驱动依赖、不需要凭据：从进程、监听端口与连接数三个只读维度
 * 感知 MySQL / PostgreSQL / Redis 的运行状态；目标环境无对应数据库时各维度优雅降级为空结果。
 * engine 参数为枚举白名单，端口/进程名均为内置映射，不存在注入面。
 */
@Component
public class DbSenseTool implements AgentTool {

    /** 引擎 -> [进程名, 默认端口] 内置映射（白名单，不接受自由输入）。 */
    private static final Map<String, DbTarget> ENGINES = Map.of(
            "mysql", new DbTarget("mysqld", 3306),
            "postgresql", new DbTarget("postgres", 5432),
            "redis", new DbTarget("redis-server", 6379));

    private final LeastPrivilegeExecutor executor;

    public DbSenseTool(LeastPrivilegeExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() { return "db_sense"; }

    @Override
    public String description() {
        return "采集数据库运行状态（免凭据只读探测）：服务进程(ps)、监听端口(ss)与已建立连接数，"
                + "支持 MySQL/PostgreSQL/Redis；可选 engine 指定引擎（默认 auto 全部探测），"
                + "目标机未部署对应数据库时优雅降级";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("type", "string");
        engine.put("description", "要探测的数据库引擎；auto 表示依次探测全部三种");
        engine.put("enum", List.of("auto", "mysql", "postgresql", "redis"));
        engine.put("default", "auto");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("engine", engine);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) {
        String engine = AgentTool.safeStrArg(input, "engine", "auto");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("engine", engine);
        if (ENGINES.containsKey(engine)) {
            r.put(engine, probe(engine, ENGINES.get(engine)));
        } else {
            for (Map.Entry<String, DbTarget> e : ENGINES.entrySet()) {
                r.put(e.getKey(), probe(e.getKey(), e.getValue()));
            }
        }
        return r;
    }

    private Map<String, Object> probe(String engine, DbTarget t) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 1. 服务进程：ps -C <comm>（无该进程时 ps 返回非 0，输出为空即「未检出」）
        String ps = executor.runReadOnly(
                List.of("ps", "-C", t.process(), "-o", "pid,user,%cpu,%mem,etime,comm")).stdout();
        boolean processUp = ps != null && ps.lines().count() > 1;
        out.put("processUp", processUp);
        out.put("process", ps);
        // 2. 监听端口：ss -tnl 过滤内置端口
        String listen = executor.runReadOnly(
                List.of("ss", "-tnl", "sport", "=", ":" + t.port())).stdout();
        out.put("listenPort", t.port());
        out.put("listening", listen);
        // 3. 已建立连接（近似连接数指标）：仅统计行数，输出不回传全量连接明细
        String estab = executor.runReadOnly(
                List.of("ss", "-tn", "state", "established", "sport", "=", ":" + t.port())).stdout();
        long connections = estab == null ? 0 : Math.max(0, estab.lines().count() - 1);
        out.put("establishedConnections", connections);
        return out;
    }

    private record DbTarget(String process, int port) {}
}
