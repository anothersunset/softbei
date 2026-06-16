package com.zhiqian.ops.guard;

import java.util.List;

/**
 * 安全护栏综合评分。按官方多层级安全权重（静态风险评估 30% / 动态意图审计 35% / 受限执行 35%）
 * 把单次请求的三层防护表现折算为 0-100 的安全分，便于量化展示并对齐评分标准。
 */
public record SecurityScore(
        int score,
        String grade,
        int staticRisk,
        int dynamicAudit,
        int restrictedExec,
        List<String> notes
) {}
