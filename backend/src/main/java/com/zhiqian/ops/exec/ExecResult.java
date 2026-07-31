package com.zhiqian.ops.exec;

/**
 * 命令执行结果。
 */
public record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean dryRun,
        long elapsedMs,
        String execId
) {
    /** 兼容旧的五参构造：无落盘标识（dry-run、空命令、超时等场景）。 */
    public ExecResult(int exitCode, String stdout, String stderr, boolean dryRun, long elapsedMs) {
        this(exitCode, stdout, stderr, dryRun, elapsedMs, null);
    }

    public boolean success() { return exitCode == 0; }
}
