package com.zhiqian.ops.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenInterceptorTest {

    @Test
    void disabled_token_allows_local_demo_requests() throws Exception {
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor(new ApiSecurityProperties());
        assertTrue(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/ops/chat"),
                new MockHttpServletResponse(), new Object()));
    }

    @Test
    void enabled_token_rejects_missing_header_and_accepts_valid_header() throws Exception {
        ApiSecurityProperties props = new ApiSecurityProperties();
        props.setApiToken("secret");
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor(props);

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(new MockHttpServletRequest("POST", "/mcp/rpc"), rejected, new Object());
        assertFalse(allowed);
        assertEquals(401, rejected.getStatus());

        MockHttpServletRequest accepted = new MockHttpServletRequest("POST", "/mcp/rpc");
        accepted.addHeader("X-Ops-Token", "secret");
        assertTrue(interceptor.preHandle(accepted, new MockHttpServletResponse(), new Object()));
    }
}
