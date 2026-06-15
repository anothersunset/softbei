package com.zhiqian.ops.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 最小权限执行器。
 * - 使用 ProcessBuilder 以 argv 数组执行，绝不拼接为 shell 字符串（从根上避免命令注入）。
 * - 高危变更可通过 sudo -u 切换到受限账户，非必要不用 root。
 * - dry-run 模式下不真正落盘执行变更类命令。
 */
@Component
public class LeastPrivilegeExecutor {
    private static final Logger log = LoggerFactory.getLogger(LeastPrivilegeExecutor.class);
    private static final int MAX_OUTPUT = 8000;

    private final ExecProperties props;

    public LeastPrivilegeExecutor(ExecProperties props) {
        this.props = props;
    }

    public ExecResult runReadOnly(List<String> argv) {
        return doRun(argv, true, props.getTimeoutSeconds());
    }

    public ExecResult run(List<String> argv) {
        return doRun(argv, false, props.getTimeoutSeconds());
    }

    private ExecResult doRun(List<String> argv, boolean readOnly, int timeoutSec) {
        String cmd = String.join(" ", argv);
        if (props.isDryRun() && !readOnly) {
            log.info("[dry-run] skip mutating command: {}", cmd);
            return new ExecResult(cmd, 0, "", "", 0L, false, "dry-run 未真正执行（演示安全模式）");
        }
        List<String> finalArgv = new ArrayList<>();
        if (!readOnly && props.isUseSudo()) {
            finalArgv.add("sudo");
            finalArgv.add("-n");
            finalArgv.add("-u");
            finalArgv.add(props.getRunAsUser());
        }
        finalArgv.addAll(argv);
        long t0 = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(finalArgv);
            String wd = props.getWorkingDir();
            if (wd != null && !wd.isBlank()) {
                File dir = new File(wd);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                }
            }
            Process p = pb.start();
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new ExecResult(cmd, 124, out, "timeout after " + timeoutSec + "s",
                        System.currentTimeMillis() - t0, true, "超时终止");
            }
            return new ExecResult(cmd, p.exitValue(), truncate(out), truncate(err),
                    System.currentTimeMillis() - t0, true, null);
        } catch (IOException e) {
            return new ExecResult(cmd, 127, "", "命令无法执行: " + e.getMessage(),
                    System.currentTimeMillis() - t0, false, "可能缺少该命令或无权限");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(cmd, 130, "", "interrupted", System.currentTimeMillis() - t0, false, null);
        }
    }

    private String readStream(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_OUTPUT ? s.substring(0, MAX_OUTPUT) + "\n...[输出已截断]" : s;
    }
}
