package com.zhiqian.ops.guard;

import java.util.List;
import java.util.Map;

/**
 * 威胁指数评估结果：随本轮指令危险度走的直觉分（高危指令分高，与 {@link SecurityScore}
 * 「防御表现分」互补但不替代——见 SecurityScorer 类注释）。
 *
 * @param index 0-100 威胁指数，越高越危险
 * @param tier  档位："低危" | "中危" | "高危" | "严重"
 * @param signals 命中信号的人话说明，按命中顺序排列，直接供前端逐行展示
 * @param axes  威胁画像三轴（0-100）：injectionSignal / commandRisk / impactScope
 */
public record ThreatAssessment(
        int index,
        String tier,
        List<String> signals,
        Map<String, Integer> axes
) {}
