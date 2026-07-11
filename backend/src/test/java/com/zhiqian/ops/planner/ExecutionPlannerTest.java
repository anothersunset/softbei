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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void derives_readonly_verification_probes_from_change_commands() throws Exception {
        ExecutionPlanner planner = new ExecutionPlanner(new IntentRiskGuard(new RiskRuleLoader()));

        ExecutionPlanner.VerifySpec restart = planner.deriveVerification("systemctl restart nginx");
        assertEquals(List.of("systemctl", "is-active", "nginx"), restart.argv());
        assertTrue(restart.passed(0));
        assertFalse(restart.passed(3));

        ExecutionPlanner.VerifySpec stop = planner.deriveVerification("systemctl stop nginx");
        assertEquals(List.of("systemctl", "is-active", "nginx"), stop.argv());
        assertTrue(stop.passed(3));
        assertFalse(stop.passed(0));

        ExecutionPlanner.VerifySpec rm = planner.deriveVerification("rm -f /var/log/app/app.log.1");
        assertEquals(List.of("stat", "/var/log/app/app.log.1"), rm.argv());
        assertTrue(rm.passed(1));

        ExecutionPlanner.VerifySpec kill = planner.deriveVerification("kill -TERM 4321");
        assertEquals(List.of("ps", "-p", "4321"), kill.argv());
        assertTrue(kill.passed(1));

        ExecutionPlanner.VerifySpec mv = planner.deriveVerification("mv /tmp/a.conf /tmp/b.conf");
        assertEquals(List.of("stat", "/tmp/b.conf"), mv.argv());
        assertTrue(mv.passed(0));

        // 无法派生（未知命令 / 含不安全字符的单元名）时返回 null，保持原有行为
        assertNull(planner.deriveVerification("echo hello"));
        assertNull(planner.deriveVerification("systemctl restart 'nginx;rm'"));
    }

    @Test
    void enable_and_unmask_probe_enablement_not_running_state() throws Exception {
        // P2 修复：enable/unmask 只改变"开机启动"配置，不代表服务当前在运行；
        // 用 is-active 会把"已启用但当前手动停止"的服务误判 VERIFY_FAILED，必须改用 is-enabled。
        ExecutionPlanner planner = new ExecutionPlanner(new IntentRiskGuard(new RiskRuleLoader()));

        ExecutionPlanner.VerifySpec enable = planner.deriveVerification("systemctl enable nginx");
        assertEquals(List.of("systemctl", "is-enabled", "nginx"), enable.argv());
        assertTrue(enable.passed(0));

        ExecutionPlanner.VerifySpec unmask = planner.deriveVerification("systemctl unmask nginx");
        assertEquals(List.of("systemctl", "is-enabled", "nginx"), unmask.argv());
        assertTrue(unmask.passed(0));

        // 对照：start/restart/reload 仍是真正的运行时动作，保持 is-active 语义不变
        ExecutionPlanner.VerifySpec reload = planner.deriveVerification("systemctl reload nginx");
        assertEquals(List.of("systemctl", "is-active", "nginx"), reload.argv());
    }

    @Test
    void non_terminating_kill_signals_skip_verification() throws Exception {
        // P2 修复：kill -CHLD/-HUP 等通知类信号进程约定处理后通常继续运行，
        // 不能假设"发出即应退出"（如 Mock 僵尸回收计划里的 kill -CHLD 4321），应跳过而非误判失败。
        ExecutionPlanner planner = new ExecutionPlanner(new IntentRiskGuard(new RiskRuleLoader()));

        assertNull(planner.deriveVerification("kill -CHLD 4321"));
        assertNull(planner.deriveVerification("kill -HUP 4321"));
        assertNull(planner.deriveVerification("kill -SIGHUP 4321"));

        // 对照：真正的终止性信号（含默认无参 TERM）仍应断言进程退出
        assertTrue(planner.deriveVerification("kill 4321").passed(1));
        assertTrue(planner.deriveVerification("kill -9 4321").passed(1));
        assertTrue(planner.deriveVerification("kill -KILL 4321").passed(1));
    }

    @Test
    void applies_verification_results_to_verify_task_status() throws Exception {
        ExecutionPlanner planner = new ExecutionPlanner(new IntentRiskGuard(new RiskRuleLoader()));
        PlanResult plan = new PlanResult();
        plan.setSteps(List.of(new PlanStep("systemctl restart nginx", "restart service")));
        OpsExecutionPlan executionPlan = planner.build("重启 nginx", plan, List.of());
        OpsTask verify = executionPlan.getTasks().stream()
                .filter(t -> "VERIFY".equals(t.getPhase())).findFirst().orElseThrow();

        planner.applyVerification(executionPlan, List.of(
                Map.of("command", "systemctl restart nginx",
                        "verifyCommand", "systemctl is-active nginx",
                        "expectation", "服务 nginx 应处于 active 状态",
                        "exitCode", 0, "passed", true)));
        assertEquals("VERIFIED", verify.getStatus());
        assertTrue(verify.getResultSummary().contains("1/1"));

        planner.applyVerification(executionPlan, List.of(
                Map.of("command", "systemctl restart nginx",
                        "verifyCommand", "systemctl is-active nginx",
                        "expectation", "服务 nginx 应处于 active 状态",
                        "exitCode", 3, "passed", false)));
        assertEquals("VERIFY_FAILED", verify.getStatus());
        assertTrue(verify.getResultSummary().contains("回滚"));

        // 空复核结果不得触碰任务状态（dry-run / mock 路径口径不变）
        verify.setStatus("PLANNED");
        planner.applyVerification(executionPlan, List.of());
        assertEquals("PLANNED", verify.getStatus());
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
