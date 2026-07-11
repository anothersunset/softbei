package com.zhiqian.ops.exec;

import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已批准的变更类多段管道必须真正以 OS 管道执行（P2）：修复前只有 READONLY 命令才有
 * {@code runReadOnlyPipeline}；变更类命令一律走单进程 {@code run(argv)}，"|" 被当成
 * 字面参数传给第一个二进制，管道右侧（如 tee）的写入从未真正发生，却报告 executed=true。
 */
class LeastPrivilegeExecutorPipelineTest {

    @TempDir
    Path tempDir;

    private String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private List<String> javaClass(String className, String... args) {
        String classPath = Path.of("target", "test-classes")
                + System.getProperty("path.separator")
                + Path.of("target", "classes");
        var argv = new java.util.ArrayList<>(List.of(javaBin(), "-cp", classPath, className));
        argv.addAll(List.of(args));
        return argv;
    }

    @Test
    void mutating_pipeline_actually_pipes_stage_output_into_next_stage() throws Exception {
        ExecProperties props = new ExecProperties();
        props.setDryRun(false);
        props.setUseSudo(false);
        props.setWorkingDir(".");
        props.setTimeoutSeconds(15);
        props.setOutputAuditDir(tempDir.resolve("audit").toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker(3, 1000));

        Path sink = tempDir.resolve("tee-output.txt");
        // 模拟"已批准的 cat/生成器 | tee <file>"：stage1 产出内容，stage2（TeeLikeMain）
        // 回显并真正写入 sink 文件——如果管道退化成字面参数传给单进程，sink 永远不会被创建。
        List<List<String>> pipeline = List.of(
                javaClass(ExecutorFloodMain.class.getName(), "3"),
                javaClass(TeeLikeMain.class.getName(), sink.toString()));

        ExecResult result = executor.runPipeline(pipeline);

        assertTrue(result.success(), () -> "pipeline should succeed: stdout=" + result.stdout() + " stderr=" + result.stderr());
        assertTrue(Files.exists(sink), "管道右侧的写入必须真正发生，sink 文件应存在");
        String written = Files.readString(sink);
        assertTrue(written.contains("OUT-0"), "sink 内容应来自 stage1 真实传递的数据：" + written);
        assertTrue(written.contains("OUT-2"), written);
    }

    @Test
    void dry_run_skips_pipeline_without_side_effects() {
        ExecProperties props = new ExecProperties();
        props.setDryRun(true);
        props.setWorkingDir(".");
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker(3, 1000));

        Path sink = tempDir.resolve("should-not-exist.txt");
        List<List<String>> pipeline = List.of(
                javaClass(ExecutorFloodMain.class.getName(), "1"),
                javaClass(TeeLikeMain.class.getName(), sink.toString()));

        ExecResult result = executor.runPipeline(pipeline);

        assertTrue(result.dryRun());
        assertEquals(0, result.exitCode());
        assertTrue(Files.notExists(sink), "dry-run 不应产生任何真实副作用");
    }

    @Test
    void single_stage_pipeline_delegates_to_run() {
        ExecProperties props = new ExecProperties();
        props.setDryRun(true);
        props.setWorkingDir(".");
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker(3, 1000));

        ExecResult result = executor.runPipeline(List.of(List.of("echo", "hi")));
        assertTrue(result.dryRun());
    }
}
