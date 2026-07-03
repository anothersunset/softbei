package com.zhiqian.ops.planner;

import com.zhiqian.ops.guard.RiskDecision;
import com.zhiqian.ops.guard.IntentRiskGuard;
import com.zhiqian.ops.guard.RiskRuleLoader;
import com.zhiqian.ops.guard.RiskLevel;
import com.zhiqian.ops.llm.PlanResult;
import com.zhiqian.ops.llm.PlanStep;
import com.zhiqian.ops.retriever.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlannerTest {

    @Test
    void builds_task_level_plan_and_attaches_guard_and_execution_state() throws Exception {
        PlanResult plan = new PlanResult();
        plan.setSteps(List.of(
                new PlanStep("df -h", "check filesystem usage"),
                new PlanStep("du -h --max-depth=1 /var/log", "find large log directories"),
                new PlanStep("rm -f /var/log/app/app.log.1", "clean rotated log")));
        ExecutionPlanner planner = new ExecutionPlanner(new IntentRiskGuard(new RiskRuleLoader()));

        OpsExecutionPlan executionPlan = planner.build("磁盘快满了，清理日志", plan,
                List.of(new Evidence("e1", "doc", "kb/runbook.md", "磁盘清理 Runbook", "先定位再清理", 1.0)));

        assertEquals(3, executionPlan.getCommandCount());
        assertTrue(executionPlan.getTasks().stream().anyMatch(t -> "OBSERVE".equals(t.getPhase())));
        assertTrue(executionPlan.getTasks().stream().anyMatch(t -> "CHANGE".equals(t.getPhase())));
        assertTrue(executionPlan.getTasks().stream().anyMatch(t -> "VERIFY".equals(t.getPhase())));
        OpsTask change = executionPlan.getTasks().stream()
                .filter(t -> "CHANGE".equals(t.getPhase()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("T1"), change.getDependsOn());

        planner.attachDecisions(executionPlan, List.of(
                new RiskDecision("df -h", RiskLevel.READONLY, "readonly", "readonly"),
                new RiskDecision("du -h --max-depth=1 /var/log", RiskLevel.READONLY, "readonly", "readonly"),
                new RiskDecision("rm -f /var/log/app/app.log.1", RiskLevel.EXECUTABLE, "change", "mutating")));

        assertEquals("EXECUTABLE", change.getExpectedRisk());
        assertEquals("WAITING_APPROVAL", change.getStatus());

        planner.attachExecutionResults(executionPlan, List.of(
                Map.of("executed", true, "dryRun", false),
                Map.of("executed", true, "dryRun", false),
                Map.of("executed", true, "dryRun", true)));

        assertEquals("DRY_RUN_EXECUTED", change.getStatus());
    }

    @Test
    void classifies_observe_and_change_tasks_using_guard_command_surface() throws Exception {
        PlanResult plan = new PlanResult();
        plan.setSteps(List.of(
                new PlanStep("ip addr show", "inspect addresses"),
                new PlanStep("ip link set eth0 down", "disable interface")));
        ExecutionPlanner planner = new ExecutionPlanner(new IntentRiskGuard(new RiskRuleLoader()));

        OpsExecutionPlan executionPlan = planner.build("检查并调整网卡", plan, List.of());

        OpsTask observe = executionPlan.getTasks().stream()
                .filter(t -> "OBSERVE".equals(t.getPhase()))
                .findFirst()
                .orElseThrow();
        OpsTask change = executionPlan.getTasks().stream()
                .filter(t -> "CHANGE".equals(t.getPhase()))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("ip addr show"), observe.getCommands());
        assertEquals(List.of("ip link set eth0 down"), change.getCommands());
    }
}
