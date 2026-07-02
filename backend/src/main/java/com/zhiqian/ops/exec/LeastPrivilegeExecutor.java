package com.zhiqian.ops.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 最小权限代理执行器。
 * 关键安全设计：始终以 argv 数组方式调用 ProcessBuilder，不经过 shell 解释，
 * 从根本上避免命令拼接/注入；可配置以受限账号（sudo -u）降权运行。
 * 兜底机制：变更类真实执行接入熔断器，连续失败时短路高危执行，避免故障级联。
 */
@Component
public class LeastPrivilegeExecutor {
    private static final Logger log = LoggerFactory.getLogger(LeastPrivilegeExecutor.class);
    private final ExecProperties props;
    private final CircuitBreaker breaker;

    public LeastPrivilegeExecutor(ExecProperties props, CircuitBreaker breaker) {
        this.props = props;
        this.breaker = breaker;
    }

    /** 执行只读/感知类命令（不受 dry-run 与熔断影响）。 */
    public ExecResult runReadOnly(List<String> argv) {
        return exec(argv, true);
    }

    /** 执行变更类命令（dry-run 打开时不真正执行；接入熔断兜底）。 */
    public ExecResult run(List<String> argv) {
        return exec(argv, false);
    }

    private ExecResult exec(List<String> argv, boolean readOnly) {
        long start = System.currentTimeMillis();
        if (argv == null || argv.isEmpty()) {
            return new ExecResult(-1, "", "空命令", false, 0);
        }
        if (!readOnly && props.isDryRun()) {
            log.info("[dry-run] skip mutating command: {}", String.join(" ", argv));
            return new ExecResult(0, "[dry-run] 已跳过实际执行：" + String.join(" ", argv), "", true, System.currentTimeMillis() - start);
        }
        // 兜底熔断：仅对变更类真实执行生效；dry-run 已在上方提前返回，只读命令亦不参与，保证评测可复现。
        if (!readOnly && !breaker.allowExecution()) {
            long leftSec = breaker.remainingCooldownMillis() / 1000;
            log.warn("[circuit-open] 熔断中，拒绝变更类执行：{}", String.join(" ", argv));
            return new ExecResult(-1, "", "[circuit-open] 连续失败已触发熔断，暂停高危执行约 " + leftSec + "s，请人工介入排查后再试", false, System.currentTimeMillis() - start);
        }
        ExecResult result;
        List<String> cmd = new ArrayList<>();
        if (props.isUseSudo()) {
            cmd.add("sudo");
            cmd.add("-n");
            cmd.add("-u");
            cmd.add(props.getRunAsUser());
        }
        cmd.addAll(argv);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(props.getWorkingDir()));
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String execId = UUID.randomUUID().toString();
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> drainStream(p.getInputStream(), execId, "stdout"));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> drainStream(p.getErrorStream(), execId, "stderr"));
            boolean finished = p.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                stdout.cancel(true);
                stderr.cancel(true);
                result = new ExecResult(-1, "", "执行超时（>" + props.getTimeoutSeconds() + "s）", false, System.currentTimeMillis() - start);
            } else {
                result = new ExecResult(p.exitValue(), stdout.join(), stderr.join(), false, System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            result = new ExecResult(-1, "", "执行异常：" + e.getMessage(), false, System.currentTimeMillis() - start);
        }
        // 记录变更类真实执行的成败，驱动熔断器状态机
        if (!readOnly) {
            if (result.success()) {
                breaker.recordSuccess();
            } else {
                breaker.recordFailure();
            }
        }
        return result;
    }

    private String drainStream(InputStream in, String execId, String streamName) {
        int previewLimit = Math.max(1024, props.getOutputPreviewBytes());
        Path auditPath = PathsSafe.outputPath(props.getOutputAuditDir(), execId, streamName);
        ByteArrayOutputStream preview = new ByteArrayOutputStream(Math.min(previewLimit, 8192));
        long total = 0;
        boolean truncated = false;
        try {
            Files.createDirectories(auditPath.getParent());
            try (InputStream source = in;
                 OutputStream audit = Files.newOutputStream(auditPath)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = source.read(buf)) != -1) {
                    audit.write(buf, 0, n);
                    total += n;
                    int remaining = previewLimit - preview.size();
                    if (remaining > 0) {
                        preview.write(buf, 0, Math.min(remaining, n));
                    }
                    if (preview.size() >= previewLimit && total > previewLimit) {
                        truncated = true;
                    }
                }
            }
        } catch (Exception e) {
            return "[读取输出失败: " + e.getMessage() + "]";
        }
        String out = preview.toString(StandardCharsets.UTF_8);
        if (truncated) {
            out += "\n[output-truncated] 前端仅展示前 " + previewLimit
                    + " bytes；完整 " + total + " bytes 已落盘: " + auditPath;
        } else {
            out += "\n[output-audit] 完整 " + total + " bytes 已落盘: " + auditPath;
        }
        return out;
    }

    private static final class PathsSafe {
        private static Path outputPath(String dir, String execId, String streamName) {
            String safeStream = streamName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String safeId = execId.replaceAll("[^a-zA-Z0-9._-]", "_");
            return Path.of(dir == null || dir.isBlank() ? "logs/exec-output" : dir)
                    .resolve(safeId + "-" + safeStream + ".log")
                    .normalize();
        }
    }
}
