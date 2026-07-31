package com.zhiqian.ops.pipeline;

/** 运维请求的权威最终状态，描述实际副作用与验证结果。 */
public enum OpsOutcomeStatus {
    CLARIFICATION_REQUIRED("目标、范围或证据存在歧义，未调用模型或生成变更计划。"),
    OBSERVED("只读检查完成，未修改系统。"),
    PLAN_FAILED("计划生成或解析失败，未执行任何操作。"),
    PIPELINE_FAILED("处理链路发生异常，已停止后续操作。"),
    REVIEW_PENDING("方案包含受控变更，正在等待人工确认，尚未执行变更。"),
    BLOCKED("方案命中安全红线，已拒绝执行。"),
    INJECTION_BLOCKED("检测到提示词注入或高危恶意意图，已在入口拦截。"),
    DRY_RUN_COMPLETED("模拟执行完成，未修改系统。"),
    EXECUTION_FAILED("命令执行失败，处置未完成。"),
    EXECUTED_UNVERIFIED("变更已执行，但尚无充分验证证据。"),
    VERIFY_FAILED("变更已执行，但执行后验证失败。"),
    VERIFIED("变更已执行并验证通过。"),
    UNKNOWN("结果状态无法确定，已按未完成处理。");

    private final String message;

    OpsOutcomeStatus(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
