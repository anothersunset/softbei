package com.zhiqian.ops.inspect;

/**
 * 一条带时间戳并已分类的系统错误日志事件，用于跨源「时间窗口关联」。
 * kind 取值：
 *   OOM       内存耗尽 / OOM Killer 触发
 *   IO        磁盘或块设备 I/O 错误（含文件系统错误）
 *   DISK_FULL 空间写满 / 配额超限
 *   OTHER     其他错误级日志
 */
public record LogEvent(
        String time,        // ISO-8601 时间戳(来自 journalctl -o short-iso)；无法解析时为空串
        long epochMillis,   // 解析出的毫秒时间戳；解析失败为 -1
        String kind,        // OOM / IO / DISK_FULL / NETWORK / DEPENDENCY / CONFIG / OTHER
        String message      // 原始日志行(截断)
) {}
