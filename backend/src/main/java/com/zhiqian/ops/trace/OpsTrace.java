package com.zhiqian.ops.trace;

import com.zhiqian.ops.agent.AgentStep;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次运维会话的完整溯源记录：从指令接收到根因分析的闭环。
 */
public class OpsTrace {
    private String traceId;
    private String instruction;
    private long startEpochMs;
    private String finalStatus;
    private final List<AgentStep> steps = new ArrayList<>();

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }
    public long getStartEpochMs() { return startEpochMs; }
    public void setStartEpochMs(long startEpochMs) { this.startEpochMs = startEpochMs; }
    public String getFinalStatus() { return finalStatus; }
    public void setFinalStatus(String finalStatus) { this.finalStatus = finalStatus; }
    public List<AgentStep> getSteps() { return steps; }
}
