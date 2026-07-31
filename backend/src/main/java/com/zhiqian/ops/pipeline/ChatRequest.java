package com.zhiqian.ops.pipeline;

import java.util.List;

/**
 * 运维对话请求。confirm=true 且携带 traceId 时，表示对之前待确认的变更计划进行人工确认执行；
 * 高影响计划还必须携带固定的 highImpactConfirmation 明示确认短语；
 * selectedTargets 非空且携带 traceId 时，表示对之前返回的磁盘候选表提交了用户选择。
 */
public class ChatRequest {
    private String instruction;
    private boolean confirm = false;
    private String traceId;
    /** 高影响操作的明示确认短语；普通 EXECUTABLE 计划不需要。 */
    private String highImpactConfirmation;
    /** 前端页面加载时生成，标识一次连续对话；用于跨轮攻击检测的会话隔离。 */
    private String sessionId;
    /** 用户从候选表中勾选的目标路径；服务端会校验其是否为候选表实际展示过的路径子集。 */
    private List<String> selectedTargets;

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public boolean isConfirm() { return confirm; }
    public void setConfirm(boolean confirm) { this.confirm = confirm; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getHighImpactConfirmation() { return highImpactConfirmation; }
    public void setHighImpactConfirmation(String highImpactConfirmation) { this.highImpactConfirmation = highImpactConfirmation; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<String> getSelectedTargets() { return selectedTargets; }
    public void setSelectedTargets(List<String> selectedTargets) { this.selectedTargets = selectedTargets; }
}
