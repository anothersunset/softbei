package com.zhiqian.ops.pipeline;

/**
 * 运维对话请求。confirm=true 且携带 traceId 时，表示对之前 REVIEW 挂起的计划进行人工确认执行。
 */
public class ChatRequest {
    private String instruction;
    private boolean confirm = false;
    private String traceId;

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public boolean isConfirm() { return confirm; }
    public void setConfirm(boolean confirm) { this.confirm = confirm; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
