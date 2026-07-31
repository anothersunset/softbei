package com.zhiqian.ops.guard;

import com.zhiqian.ops.pipeline.SessionContext;
import com.zhiqian.ops.pipeline.Turn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 跨轮渐进式攻击累积检测：确定性规则、不调模型，成本零、结果可复现，与项目「确定性规则打底、
 * LLM 兜底」的分层哲学一致。四类信号见类内常量注释；触发阈值与各项分值均来自
 * {@link CrossTurnProperties}，真机可调无需重编译。
 */
@Component
public class CrossTurnThreatMonitor {

    /**
     * 弱信号关键词：命中「暗示权限/信任已提升」类表述，但未达 {@link PromptInjectionDetector}
     * 强规则匹配阈值（强规则已覆盖"你现在是""扮演/假装+管理员/root""忽略+安全/护栏"等直白话术，
     * 见 risk-rules.yaml injectionPatterns）。此列表刻意与强规则不重叠，只捕捉更隐晦的铺垫式表述，
     * 例如"你现在权限更高了"这类不直接触发强规则、但仍在为后续危险请求做信任铺垫的说法。
     * 纯数据，可按真机测试情况自行增删条目，不涉及架构改动。
     */
    private static final List<Pattern> WEAK_SIGNAL_PATTERNS = List.of(
            Pattern.compile("权限.{0,6}(更高|更大|提升|升级)"),
            Pattern.compile("(解除|取消|放开).{0,6}限制"),
            Pattern.compile("(不用|无需|不需要).{0,6}(那么|这么)?(谨慎|小心|担心|顾虑)"),
            Pattern.compile("(信任|相信)\\s*我"),
            Pattern.compile("(这次|这一次|特殊情况下?).{0,10}(可以|能|允许).{0,6}(例外|放宽|不受限)")
    );

    private final CrossTurnProperties props;

    public CrossTurnThreatMonitor(CrossTurnProperties props) {
        this.props = props;
    }

    /**
     * 计算本轮增量分并累加到会话可疑度，返回本轮评估结果。
     *
     * @param session       目标会话（累积可疑度就地更新）
     * @param instruction   本轮原始指令（脱敏后），仅用于弱信号关键词匹配，不做其它用途
     * @param injectionHit  本轮是否已被 PromptInjectionDetector 判定拦截
     * @param thisRisk      本轮四级风险裁决（worstLevel）
     * @param thisTargetSlot 本轮操作目标槽位（见 {@link #extractTargetSlot}）
     */
    public Assessment assess(SessionContext session, String instruction, boolean injectionHit,
                              RiskLevel thisRisk, String thisTargetSlot) {
        List<String> reasons = new ArrayList<>();
        int delta = 0;

        if (injectionHit) {
            delta += props.getInjectionHitScore();
            reasons.add("单轮命中提示词注入/红线特征(+" + props.getInjectionHitScore() + ")");
        }

        List<Turn> history = session.cleanHistorySnapshot();
        Turn lastClean = history.isEmpty() ? null : history.get(history.size() - 1);

        if (lastClean != null && thisRisk != null && lastClean.risk() != null) {
            int levels = thisRisk.severity() - lastClean.risk().severity();
            if (levels > 0) {
                int add = levels * props.getRiskEscalationScore();
                delta += add;
                reasons.add("风险等级较上一干净轮升级" + levels + "级(+" + add + ")");
            }
        }

        if (lastClean != null && thisTargetSlot != null && !thisTargetSlot.isBlank()
                && lastClean.targetSlot() != null && !lastClean.targetSlot().isBlank()
                && !thisTargetSlot.equalsIgnoreCase(lastClean.targetSlot())) {
            delta += props.getTargetJumpScore();
            reasons.add("操作目标相对上一干净轮跳变：" + lastClean.targetSlot() + " -> " + thisTargetSlot
                    + "(+" + props.getTargetJumpScore() + ")");
        }

        // 弱信号与单轮注入命中互斥计分：避免同一句话的语义被强/弱两条规则重复加分。
        if (!injectionHit && matchesWeakSignal(instruction)) {
            delta += props.getWeakSignalScore();
            reasons.add("命中权限/信任铺垫类弱信号(+" + props.getWeakSignalScore() + ")");
        }

        if (delta != 0) {
            session.addSuspicion(delta);
        }
        int total = session.suspicion();
        boolean triggered = total >= props.getLockdownThreshold();
        String reason = reasons.isEmpty() ? null
                : "累积可疑度 " + total + "/" + props.getLockdownThreshold() + "：" + String.join("；", reasons);
        return new Assessment(delta, total, triggered, reason);
    }

    private boolean matchesWeakSignal(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return false;
        }
        for (Pattern p : WEAK_SIGNAL_PATTERNS) {
            if (p.matcher(instruction).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从本轮风险裁决中提取一个粗粒度「操作目标」标识，供跨轮目标跳变比对。
     * 启发式做法：取最高风险决策命令中最后一个非选项（不以 '-' 开头）token；
     * 该 token 通常就是服务名/路径/进程名等真实操作对象（如 "systemctl restart nginx" -> "nginx"，
     * "rm -rf /var/lib/mysql" -> "/var/lib/mysql"）。取不到时返回 null（不参与跳变比对，不误伤）。
     * 这是一个粗粒度信号，不是安全边界本身——真正的红线判定仍由 IntentRiskGuard 对生成命令做确定性裁决。
     */
    public String extractTargetSlot(List<RiskDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return null;
        }
        RiskDecision top = decisions.get(0);
        for (RiskDecision d : decisions) {
            if (d.level() != null && top.level() != null && d.level().severity() > top.level().severity()) {
                top = d;
            }
        }
        String command = top.command();
        if (command == null || command.isBlank()) {
            return null;
        }
        String[] tokens = command.trim().split("\\s+");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String t = tokens[i];
            if (!t.isEmpty() && t.charAt(0) != '-') {
                return t.length() > 80 ? t.substring(0, 80) : t;
            }
        }
        return null;
    }

    public record Assessment(int delta, int totalSuspicion, boolean triggered, String reason) {}
}
