package com.zhiqian.ops.guard;

import java.util.List;

/**
 * 提示词注入检测结果。
 */
public record InjectionResult(
        boolean blocked,
        List<String> matchedPatterns,
        String reason
) {}
