package com.zhiqian.ops.exec;

/**
 * 命令执行结果。
 */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean dryRun,
        long elapsedMs
) {
    public boolean success() { return exitCode == 0; }
}
