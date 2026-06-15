package com.zhiqian.ops.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 最小权限代理执行器。
 * 关键安全设计：始终以 argv 数组方式调用 ProcessBuilder，不经过 shell 解释，
 * 从根本上避免命令拼接/注入；可配置以受限账号（sudo -u）降权运行。
 */
@Component
public class LeastPrivilegeExecutor {
    private static final Logger log = LoggerFactory.getLogger(LeastPrivilegeExecutor.class);
    private final ExecProperties props;

    public LeastPrivilegeExecutor(ExecProperties props) {
        this.props = props;
    }

    /** 执行只读/感知类命令（不受 dry-run 影响）。 */
    public ExecResult runReadOnly(List<String> argv) {
        return exec(argv, true);
    }

    /** 执行变更类命令（dry-run 打开时不真正执行）。 */
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
            boolean finished = p.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new ExecResult(-1, "", "执行超时（>" + props.getTimeoutSeconds() + "s）", false, System.currentTimeMillis() - start);
            }
            String out = readStream(p.getInputStream());
            String err = readStream(p.getErrorStream());
            return new ExecResult(p.exitValue(), out, err, false, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new ExecResult(-1, "", "执行异常：" + e.getMessage(), false, System.currentTimeMillis() - start);
        }
    }

    private String readStream(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines < 500) {
                sb.append(line).append('\n');
                lines++;
            }
        } catch (Exception e) {
            sb.append("[读取输出失败: ").append(e.getMessage()).append("]");
        }
        return sb.toString();
    }
}
