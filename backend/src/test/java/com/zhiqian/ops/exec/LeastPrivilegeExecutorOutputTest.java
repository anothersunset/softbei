package com.zhiqian.ops.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.SensitiveDataSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void readonly_pipeline_connects_stdout_to_next_stage_without_shell_parsing() {
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
        ExecResult result = executor.runReadOnlyPipeline(List.of(
                List.of(javaBin, "-cp", classPath, ExecutorFloodMain.class.getName(), "50"),
                List.of(javaBin, "-cp", classPath, LineFilterMain.class.getName(), "OUT-42")));

        assertTrue(result.success(), () -> "stdout=" + result.stdout() + "\nstderr=" + result.stderr());
        assertTrue(result.stdout().contains("OUT-42"));
        assertFalse(result.stdout().contains("OUT-41"));
        assertFalse(result.stdout().contains("OUT-43"));
        assertTrue(result.stderr().contains("[pipe-stage-1]"));
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

    @Test
    void audit_files_are_sanitized_before_being_written_to_disk() throws Exception {
        ExecProperties props = new ExecProperties();
        props.setDryRun(false);
        props.setUseSudo(false);
        props.setWorkingDir(".");
        props.setTimeoutSeconds(10);
        props.setOutputAuditDir(tempDir.toString());
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(new RiskRuleLoader());
        LeastPrivilegeExecutor executor = new LeastPrivilegeExecutor(props, new CircuitBreaker(3, 1000), sanitizer);

        String javaBin = Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
        String classPath = Path.of("target", "test-classes")
                + System.getProperty("path.separator")
                + Path.of("target", "classes");
        ExecResult result = executor.runReadOnly(List.of(
                javaBin, "-cp", classPath, SecretEchoMain.class.getName()));

        assertTrue(result.success(), () -> "stdout=" + result.stdout() + "\nstderr=" + result.stderr());
        assertTrue(result.stdout().contains("password=***"));
        assertTrue(result.stdout().contains("-----BEGIN PRIVATE KEY-----***"));
        assertTrue(result.stdout().contains("-----END PRIVATE KEY-----"));
        assertFalse(result.stdout().contains("plain-secret"));
        assertFalse(result.stdout().contains("private-key-body-should-not-leak"));
        assertFalse(result.stderr().contains("stderr-token"));

        String allAudit = Files.list(tempDir)
                .map(p -> {
                    try {
                        return Files.readString(p);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce("", String::concat);
        assertTrue(allAudit.contains("password=***"));
        assertTrue(allAudit.contains("token=***"));
        assertTrue(allAudit.contains("-----BEGIN PRIVATE KEY-----***"));
        assertTrue(allAudit.contains("-----END PRIVATE KEY-----"));
        assertFalse(allAudit.contains("plain-secret"));
        assertFalse(allAudit.contains("private-key-body-should-not-leak"));
        assertFalse(allAudit.contains("stderr-token"));
    }

    private boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
