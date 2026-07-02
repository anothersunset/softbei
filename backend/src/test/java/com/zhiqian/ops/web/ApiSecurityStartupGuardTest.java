package com.zhiqian.ops.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiSecurityStartupGuardTest {

    @Test
    void loopback_bind_without_token_is_allowed() {
        ApiSecurityProperties props = new ApiSecurityProperties();
        ApiSecurityStartupGuard guard = new ApiSecurityStartupGuard(
                props,
                new MockEnvironment().withProperty("server.address", "127.0.0.1"));

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void non_loopback_bind_without_token_refuses_startup() {
        ApiSecurityProperties props = new ApiSecurityProperties();
        ApiSecurityStartupGuard guard = new ApiSecurityStartupGuard(
                props,
                new MockEnvironment().withProperty("server.address", "0.0.0.0"));

        assertThrows(IllegalStateException.class, () -> guard.run(null));
    }

    @Test
    void non_loopback_bind_with_token_is_allowed() {
        ApiSecurityProperties props = new ApiSecurityProperties();
        props.setApiToken("secret");
        ApiSecurityStartupGuard guard = new ApiSecurityStartupGuard(
                props,
                new MockEnvironment().withProperty("server.address", "0.0.0.0"));

        assertDoesNotThrow(() -> guard.run(null));
    }

    @Test
    void loopback_detection_handles_common_addresses() {
        assertTrue(ApiSecurityStartupGuard.isLoopbackBind("localhost"));
        assertTrue(ApiSecurityStartupGuard.isLoopbackBind("::1"));
        assertFalse(ApiSecurityStartupGuard.isLoopbackBind("0.0.0.0"));
        assertFalse(ApiSecurityStartupGuard.isLoopbackBind("::"));
    }
}
