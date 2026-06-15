package com.zhiqian.ops.llm;

/**
 * 推理生成的单步运维计划：一条拟执行命令 + 用途说明。
 */
public class PlanStep {
    private String command;
    private String purpose;

    public PlanStep() {}

    public PlanStep(String command, String purpose) {
        this.command = command;
        this.purpose = purpose;
    }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
}
