package com.zhiqian.ops.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LeastPrivilegeExecutorOutputTest {

    @TempDir
    Path tempDir;

    @Test
    void drains_stdout_and_stderr_concurrently_without_line_truncation() {
        ExecProperties props = new ExecProperties();
        props.setDryRun(false);
        props.setUseSudo(false);
        props.setWorkingDir(".");
        props.setTimeoutSeconds(10);
        props.setOutputAuditDir(tempDir.toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker(3, 1000));

        String javaBin = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
        String classPath = Path.of("target", "test-classes")
                + System.getProperty("path.separator")
                + Path.of("target", "classes");
        ExecResult result = executor.runReadOnly(List.of(
                javaBin, "-cp", classPath, ExecutorFloodMain.class.getName(), "900"));

        assertTrue(result.success(), () -> "stdout=" + result.stdout() + "\nstderr=" + result.stderr());
        assertTrue(result.stdout().contains("OUT-899"), "stdout tail evidence should be preserved");
        assertTrue(result.stderr().contains("ERR-899"), "stderr tail evidence should be preserved");
    }

    @Test
    void caps_in_memory_preview_while_full_output_is_written_to_audit_file() throws Exception {
        ExecProperties props = new ExecProperties();
        props.setDryRun(false);
        props.setUseSudo(false);
        props.setWorkingDir(".");
        props.setTimeoutSeconds(10);
        props.setOutputPreviewBytes(2048);
        props.setOutputAuditDir(tempDir.toString());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker(3, 1000));

        String javaBin = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
        String classPath = Path.of("target", "test-classes")
                + System.getProperty("path.separator")
                + Path.of("target", "classes");
        ExecResult result = executor.runReadOnly(List.of(
                javaBin, "-cp", classPath, ExecutorFloodMain.class.getName(), "900"));

        assertTrue(result.success(), () -> "stdout=" + result.stdout() + "\nstderr=" + result.stderr());
        assertTrue(result.stdout().contains("[output-truncated]"));
        assertTrue(result.stderr().contains("[output-truncated]"));

        Path stdoutAudit = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith("-stdout.log"))
                .findFirst()
                .orElseThrow();
        Path stderrAudit = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith("-stderr.log"))
                .findFirst()
                .orElseThrow();
        assertTrue(Files.readString(stdoutAudit).contains("OUT-899"));
        assertTrue(Files.readString(stderrAudit).contains("ERR-899"));
    }

    private boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
