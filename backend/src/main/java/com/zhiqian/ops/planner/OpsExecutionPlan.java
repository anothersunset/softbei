package com.zhiqian.ops.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit task decomposition used to make the Plan-and-Execute layer visible.
 */
public class OpsExecutionPlan {
    private String strategy;
    private String executionMode;
    private String summary;
    private int commandCount;
    private List<OpsTask> tasks = new ArrayList<>();

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getCommandCount() { return commandCount; }
    public void setCommandCount(int commandCount) { this.commandCount = commandCount; }
    public List<OpsTask> getTasks() { return tasks; }
    public void setTasks(List<OpsTask> tasks) { this.tasks = tasks == null ? new ArrayList<>() : tasks; }
}
