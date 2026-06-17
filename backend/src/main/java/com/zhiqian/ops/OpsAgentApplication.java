package com.zhiqian.ops;

import com.zhiqian.ops.mcp.McpStdioServer;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OS 智能运维 Agent 启动类。
 * B/S 架构：后端同时提供 REST(/api/ops/**) 与 MCP(/mcp/rpc) 两类入口。
 * @EnableScheduling 支持定时主动巡检（默认关闭，需 ops.inspect.scheduled-enabled=true 开启）。
 *
 * 另支持以 stdio 传输层启动的插件模式：
 *   java -jar ops-agent.jar --mcp-stdio
 * 此模式不启动 Web 服务器，而是从 stdin 读取 JSON-RPC、向 stdout 写响应，
 * 体现「MCP 运维插件化」：可作为独立巡检插件进程被主 Agent 拉起并通信。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OpsAgentApplication {
    public static void main(String[] args) {
        boolean stdioMode = false;
        for (String a : args) {
            if ("--mcp-stdio".equals(a)) {
                stdioMode = true;
                break;
            }
        }

        if (stdioMode) {
            // stdout 专用于 JSON-RPC 响应，关闭日志以免污染协议流
            System.setProperty("logging.level.root", "OFF");
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(OpsAgentApplication.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .run(args);
            McpStdioServer server = ctx.getBean(McpStdioServer.class);
            int code = server.serve(System.in, System.out);
            ctx.close();
            System.exit(code);
        } else {
            SpringApplication.run(OpsAgentApplication.class, args);
        }
    }
}
