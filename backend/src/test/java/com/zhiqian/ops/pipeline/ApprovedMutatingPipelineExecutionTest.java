package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.agent.AgentContext;
import com.zhiqian.ops.agent.AgentTool;
import com.zhiqian.ops.agent.ToolRegistry;
import com.zhiqian.ops.analyzer.RootCauseAnalyzer;
import com.zhiqian.ops.exec.CircuitBreaker;
import com.zhiqian.ops.exec.ExecProperties;
import com.zhiqian.ops.exec.ExecutorFloodMain;
import com.zhiqian.ops.exec.LeastPrivilegeExecutor;
import com.zhiqian.ops.exec.TeeLikeMain;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.PromptInjectionDetector;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import com.zhiqian.ops.llm.LlmClient;
import com.zhiqian.ops.mcp.McpDispatcher;
import com.zhiqian.ops.retriever.ContextRetriever;
import com.zhiqian.ops.trace.OpsAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证：已确认的变更类管道命令通过完整 OpsPipeline 确认执行流程后，
 * 管道右侧的写入必须真正发生（P2，对应 LeastPrivilegeExecutor.runPipeline 的路由修复）。
 */
class ApprovedMutatingPipelineExecutionTest {

    @TempDir
    Path tempDir;

    private String classpath() {
        // 必须用绝对路径：本测试把 execProps.workingDir 设为临时目录（sink 文件所在处），
        // 子进程的相对路径解析以该工作目录为准，相对 classpath 会导致 ClassNotFoundException。
        return Path.of("target", "test-classes").toAbsolutePath()
                + System.getProperty("path.separator") + Path.of("target", "classes").toAbsolutePath();
    }

    private String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    /**
     * 固定返回一条"java 生成器 | java tee-like 写文件"管道命令的 LLM 桩。
     * 用 Jackson 构造 JSON（而非手工拼字符串转义）：命令本身含双引号（-cp "..."）与反斜杠
     * （Windows 路径），手工转义容易漏掉某一类字符、产出损坏的 JSON。
     */
    private final class PipeCommandStub implements LlmClient {
        private final String command;
        private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        PipeCommandStub(String command) { this.command = command; }
        @Override public boolean isReal() { return false; }
        @Override public String providerName() { return "pipe-stub"; }
        @Override public String chat(String prompt) {
            try {
                var step = mapper.createObjectNode().put("command", command).put("purpose", "确认后执行管道");
                var steps = mapper.createArrayNode().add(step);
                return mapper.createObjectNode()
                        .put("summary", "pipe")
                        .put("rootCauseHypothesis", "none")
                        .put("confidence", 0.9)
                        .set("steps", steps)
                        .toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void confirmed_pipeline_command_actually_writes_via_second_stage() throws Exception {
        Path sink = tempDir.resolve("pipeline-sink.txt");
        // classpath 必须加引号：Windows 的 path.separator 是 ';'，护栏的管道/连接符解析器
        // 把裸露的 ';' 当作命令连接符是正确且安全的保守行为——未加引号的含特殊字符实参本身
        // 就是有歧义的命令，护栏理应保守拒绝识别为单组管道；加引号是任何规范命令都该做的。
        String command = javaBin() + " -cp \"" + classpath() + "\" " + ExecutorFloodMain.class.getName() + " 2"
                + " | " + javaBin() + " -cp \"" + classpath() + "\" " + TeeLikeMain.class.getName() + " " + sink;

        RiskRuleLoader rules = new RiskRuleLoader();
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(rules);
        ExecProperties execProps = new ExecProperties();
        execProps.setDryRun(false); // 需要真实落地才能验证管道右侧确实写入
        execProps.setWorkingDir(tempDir.toString());
        execProps.setTimeoutSeconds(15);
        IntentRiskGuard guard = new IntentRiskGuard(rules);
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(execProps, new CircuitBreaker(3, 30_000));
        OpsAuditService audit = new OpsAuditService(tempDir.resolve("trace.jsonl").toString());
        List<AgentTool> senseTools = List.of(new FakeSenseTool());

        OpsPipeline pipeline = new OpsPipeline(
                new com.zhiqian.ops.agent.AgentRunner(),
                new PromptInjectionDetector(rules),
                guard,
                new PipeCommandStub(command),
                new RootCauseAnalyzer(),
                audit,
                executor,
                senseTools,
                new ContextRetriever(audit, rules),
                execProps,
                sanitizer,
                new McpDispatcher(new ToolRegistry(senseTools), sanitizer));

        ChatRequest first = new ChatRequest();
        first.setInstruction("请执行受控管道命令");
        ChatResponse pending = pipeline.chat(first);
        assertEquals("REVIEW_PENDING", pending.getStatus(), "tee 段为变更类，应先要求人工确认");

        ChatRequest confirm = new ChatRequest();
        confirm.setInstruction("确认执行");
        confirm.setTraceId(pending.getTraceId());
        confirm.setConfirm(true);
        ChatResponse executed = pipeline.chat(confirm);

        assertEquals("EXECUTED", executed.getStatus());
        Map<String, Object> execResult = executed.getExecResults().get(0);
        assertEquals(Boolean.TRUE, execResult.get("executed"));

        assertTrue(Files.exists(sink), "管道右侧（tee-like 第二阶段）必须真正写入 sink 文件，而不是被当成字面参数。"
                + "实际执行输出：" + execResult.get("output"));
        String written = Files.readString(sink);
        assertTrue(written.contains("OUT-0") && written.contains("OUT-1"),
                "sink 内容应来自第一阶段真实传递的数据：" + written);
    }

    private static final class FakeSenseTool implements AgentTool {
        @Override public String name() { return "fake_system_sense"; }
        @Override public String description() { return "test sense tool"; }
        @Override public Map<String, Object> run(AgentContext ctx, Map<String, Object> input) { return Map.of("ok", true); }
    }
}
