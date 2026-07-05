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

import java.io.PrintStream;

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
            // stdout 必须是纯净的 JSON-RPC 协议流。Spring/logback 的 ConsoleAppender 默认写 System.out，
            // 且启动早期日志在 logging.level 生效前就会输出，单靠关日志级别不足以防污染。
            // 因此：先保留真正的 stdout 给协议流，再把 System.out 重定向到 stderr，
            // 这样所有框架日志（含启动早期日志）都落到 stderr，不破坏 JSON-RPC 结构。
            PrintStream protocolOut = System.out;
            System.setOut(System.err);
            System.setProperty("logging.level.root", "WARN");
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(OpsAgentApplication.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .run(args);
            McpStdioServer server = ctx.getBean(McpStdioServer.class);
            int code = server.serve(System.in, protocolOut);
            ctx.close();
            System.exit(code);
        } else {
            SpringApplication.run(OpsAgentApplication.class, args);
        }
    }
}
