package com.zhiqian.ops.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * A task-level unit in the explicit Plan-and-Execute view.
 */
public class OpsTask {
    private String id;
    private String phase;
    private String title;
    private String objective;
    private List<String> commands = new ArrayList<>();
    private List<Integer> commandIndexes = new ArrayList<>();
    private List<String> dependsOn = new ArrayList<>();
    private List<String> evidenceRefs = new ArrayList<>();
    private String expectedRisk = "UNKNOWN";
    private String status = "PLANNED";
    private String resultSummary;

    public OpsTask() {}

    public OpsTask(String id, String phase, String title, String objective) {
        this.id = id;
        this.phase = phase;
        this.title = title;
        this.objective = objective;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }
    public List<String> getCommands() { return commands; }
    public void setCommands(List<String> commands) { this.commands = commands == null ? new ArrayList<>() : commands; }
    public List<Integer> getCommandIndexes() { return commandIndexes; }
    public void setCommandIndexes(List<Integer> commandIndexes) {
        this.commandIndexes = commandIndexes == null ? new ArrayList<>() : commandIndexes;
    }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn == null ? new ArrayList<>() : dependsOn; }
    public List<String> getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs == null ? new ArrayList<>() : evidenceRefs;
    }
    public String getExpectedRisk() { return expectedRisk; }
    public void setExpectedRisk(String expectedRisk) { this.expectedRisk = expectedRisk; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
}
