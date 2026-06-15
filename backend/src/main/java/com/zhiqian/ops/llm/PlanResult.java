package com.zhiqian.ops.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理阶段的结构化输出：根因假设 + 分步运维计划。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanResult {
    private String summary;
    private String rootCauseHypothesis;
    private Double confidence;
    private List<PlanStep> steps = new ArrayList<>();

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getRootCauseHypothesis() { return rootCauseHypothesis; }
    public void setRootCauseHypothesis(String rootCauseHypothesis) { this.rootCauseHypothesis = rootCauseHypothesis; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }
}
