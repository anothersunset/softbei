package com.zhiqian.ops.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * lsof 写入者探测的共享解析逻辑：解析 `lsof -F pcu <path>` 输出（p=pid, c=command, u=uid），
 * 供 io_activity_sense 工具与磁盘候选表增长复检共用，避免同一段解析逻辑在两处漂移。
 */
public final class LsofWriters {
    private LsofWriters() {}

    /** 探测失败（lsof 不可用/权限不足）时返回空表，不抛出、不阻断调用方主链路。 */
    public static List<Map<String, Object>> probe(LeastPrivilegeExecutor executor, String path) {
        List<Map<String, Object>> writers = new ArrayList<>();
        try {
            String stdout = executor.runReadOnly(List.of("lsof", "-F", "pcu", path)).stdout();
            if (stdout == null || stdout.isBlank()) {
                return writers;
            }
            String pid = null, comm = null, user = null;
            for (String line : stdout.split("\n")) {
                if (line.isEmpty()) continue;
                char tag = line.charAt(0);
                String value = line.substring(1);
                switch (tag) {
                    case 'p' -> {
                        if (pid != null) {
                            writers.add(entry(pid, comm, user));
                        }
                        pid = value; comm = null; user = null;
                    }
                    case 'c' -> comm = value;
                    case 'u' -> user = value;
                    default -> { /* 其余字段（f/a/n 等）不需要，忽略 */ }
                }
            }
            if (pid != null) {
                writers.add(entry(pid, comm, user));
            }
        } catch (Exception ignored) {
            // 见方法注释：静默降级
        }
        return writers;
    }

    private static Map<String, Object> entry(String pid, String comm, String user) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("pid", pid);
        w.put("comm", comm);
        w.put("user", user);
        return w;
    }
}
