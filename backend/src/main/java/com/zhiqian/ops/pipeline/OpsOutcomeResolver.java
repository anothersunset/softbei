package com.zhiqian.ops.pipeline;

import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.llm.PlanResult;

import java.util.List;
import java.util.Map;

/** 根据管线事实统一计算最终状态，供同步接口、SSE、trace 与报告复用。 */
public final class OpsOutcomeResolver {

    public OpsOutcomeStatus resolve(Map<String, Object> state) {
        if (Boolean.TRUE.equals(state.get("injectionBlocked"))) {
            return OpsOutcomeStatus.INJECTION_BLOCKED;
        }
        // 磁盘候选门禁短路：目标存在多个候选、需人工先选定再规划，尚未调用模型/生成计划，
        // 语义与既有 CLARIFICATION_REQUIRED（"目标、范围或证据存在歧义"）完全吻合，直接复用。
        if (Boolean.TRUE.equals(state.get("haltForTargetSelection"))) {
            return OpsOutcomeStatus.CLARIFICATION_REQUIRED;
        }
        RiskLevel worst = riskLevel(state.get("worstLevel"));
        if (worst == RiskLevel.BLOCK) {
            return OpsOutcomeStatus.BLOCKED;
        }
        if (state.get("pipelineError") != null) {
            return OpsOutcomeStatus.PIPELINE_FAILED;
        }
        if (Boolean.TRUE.equals(state.get("planFailed")) || emptyPlan(state.get("plan"))) {
            return OpsOutcomeStatus.PLAN_FAILED;
        }

        boolean confirmed = Boolean.TRUE.equals(state.get("confirm"));
        if (worst.requiresApproval() && !confirmed) {
            return OpsOutcomeStatus.REVIEW_PENDING;
        }

        Object rawResults = state.get("execResults");
        if (!(rawResults instanceof List<?> results) || results.isEmpty()) {
            return OpsOutcomeStatus.EXECUTION_FAILED;
        }

        boolean anyExecuted = false;
        boolean anyExecutionFailure = false;
        boolean anyMutation = false;
        boolean anyDryRunMutation = false;
        boolean anyRealMutation = false;
        boolean everyRealMutationVerified = true;
        boolean verificationFailed = false;

        for (Object item : results) {
            if (!(item instanceof Map<?, ?> result)) {
                continue;
            }
            if (!Boolean.TRUE.equals(result.get("executed"))) {
                anyExecutionFailure = true;
                continue;
            }
            anyExecuted = true;
            if (number(result.get("exitCode"), -1) != 0) {
                anyExecutionFailure = true;
            }

            RiskLevel level = riskLevel(result.get("level"));
            if (level == RiskLevel.READONLY) {
                continue;
            }
            anyMutation = true;
            if (Boolean.TRUE.equals(result.get("dryRun"))) {
                anyDryRunMutation = true;
                continue;
            }

            anyRealMutation = true;
            Object rawVerify = result.get("verify");
            if (rawVerify instanceof Map<?, ?> verify) {
                boolean passed = Boolean.TRUE.equals(verify.get("passed"));
                if (!passed) {
                    verificationFailed = true;
                    everyRealMutationVerified = false;
                }
            } else {
                everyRealMutationVerified = false;
            }
        }

        if (anyExecutionFailure || !anyExecuted) {
            return OpsOutcomeStatus.EXECUTION_FAILED;
        }
        if (anyMutation && anyDryRunMutation && !anyRealMutation) {
            return OpsOutcomeStatus.DRY_RUN_COMPLETED;
        }
        if (anyRealMutation) {
            if (verificationFailed) {
                return OpsOutcomeStatus.VERIFY_FAILED;
            }
            return everyRealMutationVerified
                    ? OpsOutcomeStatus.VERIFIED
                    : OpsOutcomeStatus.EXECUTED_UNVERIFIED;
        }
        return OpsOutcomeStatus.OBSERVED;
    }

    private boolean emptyPlan(Object value) {
        if (!(value instanceof PlanResult plan) || plan.getSteps() == null || plan.getSteps().isEmpty()) {
            return true;
        }
        return plan.getSteps().stream()
                .allMatch(step -> step == null || step.getCommand() == null || step.getCommand().isBlank());
    }

    private RiskLevel riskLevel(Object value) {
        if (value instanceof RiskLevel level) {
            return level;
        }
        if (value != null) {
            try {
                return RiskLevel.valueOf(String.valueOf(value));
            } catch (IllegalArgumentException ignored) {
                // Unknown values are not interpreted as successful mutations.
            }
        }
        return RiskLevel.READONLY;
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // use fallback
            }
        }
        return fallback;
    }
}
