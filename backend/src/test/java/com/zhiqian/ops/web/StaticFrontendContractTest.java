package com.zhiqian.ops.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFrontendContractTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void static_console_keeps_core_demo_surfaces() throws Exception {
        String html = Files.readString(STATIC.resolve("index.html"));
        String app = Files.readString(STATIC.resolve("app.js"));
        String stream = Files.readString(STATIC.resolve("stream.js"));

        assertTrue(html.contains("id=\"instr\""));
        assertTrue(html.contains("id=\"pipe\""));
        assertTrue(html.contains("id=\"runtimeTxt\""));
        assertTrue(html.contains("data-tab=\"inspect\""));
        assertTrue(html.contains("data-tab=\"redteam\""));
        assertTrue(html.contains("data-tab=\"stream\""));
        assertTrue(app.contains("function loadRuntime()"));
        assertTrue(app.contains("/api/ops/runtime"));
        assertTrue(app.contains("{key:'PLAN',name:'规划'}"));
        assertTrue(app.contains("executionPlanHtml"));
        assertTrue(app.contains("executionPlan"));
        assertTrue(app.contains("function runInspect()"));
        assertTrue(app.contains("function runRedteam()"));
        assertTrue(html.contains("id=\"apiToken\""));
        assertTrue(app.contains("function apiFetch"));
        assertTrue(app.contains("X-Ops-Token"));
        assertTrue(stream.contains("apiFetch('/api/ops/chat/stream"));
        assertTrue(stream.contains("/api/ops/chat/stream"));
    }
}
