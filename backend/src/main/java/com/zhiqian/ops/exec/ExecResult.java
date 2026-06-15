package com.zhiqian.ops.exec;

/**
 * 执行结果。executed=false 表示被 dry-run 跳过或无法执行。
 */
public record ExecResult(
        String command,
        int exitCode,
        String stdout,
        String stderr,
        long elapsedMs,
        boolean executed,
        String note
) {}
