package com.zhiqian.ops.web;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Enforces the deployment trust boundary at startup.
 */
@Component
public class ApiSecurityStartupGuard implements ApplicationRunner {
    private final ApiSecurityProperties props;
    private final Environment environment;

    public ApiSecurityStartupGuard(ApiSecurityProperties props, Environment environment) {
        this.props = props;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String bindAddress = environment.getProperty("server.address", "0.0.0.0");
        if (!isLoopbackBind(bindAddress) && !props.tokenEnabled()) {
            throw new IllegalStateException("Refusing to start: server.address=" + bindAddress
                    + " is not loopback and OPS_API_TOKEN is empty. Set OPS_API_TOKEN or bind to 127.0.0.1.");
        }
    }

    static boolean isLoopbackBind(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return false;
        }
        String value = bindAddress.trim();
        if ("localhost".equalsIgnoreCase(value) || "127.0.0.1".equals(value) || "::1".equals(value)) {
            return true;
        }
        if ("0.0.0.0".equals(value) || "::".equals(value) || "[::]".equals(value)) {
            return false;
        }
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
