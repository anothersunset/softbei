package com.zhiqian.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * OS 智能运维 Agent 启动类。
 * B/S 架构：后端同时提供 REST(/api/ops/**) 与 MCP(/mcp/rpc) 两类入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OpsAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsAgentApplication.class, args);
    }
}
