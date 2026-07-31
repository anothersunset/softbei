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

        // 链路三色轨道：每个节点读自身信号，不再按顶层 status 分支（见设计文档§1.3）
        assertTrue(app.contains("function deriveStageStates"));
        assertTrue(app.contains("function composeExecuteSub"));
        assertTrue(app.contains("aftermath"));
        assertTrue(html.contains("id=\"pipeLegend\""));
        assertTrue(html.contains(".stage.hold"));
        assertTrue(html.contains(".stage.aftermath"));
        assertTrue(stream.contains("function applyFinalPipeState"));

        // 威胁指数评分卡：随指令危险度走，与 securityScore（防御履职表现分）互补
        assertTrue(app.contains("threatAssessment"));
        assertTrue(app.contains("function threatCardHtml"));
        assertTrue(app.contains("function legacyScoreCardHtml"));
        assertTrue(stream.contains("threatAssessment"));
    }
}
