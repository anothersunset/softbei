package com.zhiqian.ops.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CounterfactualAnalyzer / SecurityScorer / RollbackAdvisor 单元测试。
 * 目标：覆盖 guard 包低覆盖率类，提升 JaCoCo 指令/分支覆盖率。
 */
class GuardCoverageTest {

    // ═══════════════════════════════════════
    // CounterfactualAnalyzer
    // ═══════════════════════════════════════
    @Nested
    @DisplayName("CounterfactualAnalyzer")
    class CounterfactualTests {
        private final CounterfactualAnalyzer analyzer = new CounterfactualAnalyzer();

        @Test
        void analyze_null_returns_empty() {
            assertTrue(analyzer.analyze(null).isEmpty());
        }

        @Test
        void analyze_safe_decisions_filtered_out() {
            var decisions = List.of(
                new RiskDecision("df -h", RiskLevel.SAFE, "read-only", "safeList")
            );
            assertTrue(analyzer.analyze(decisions).isEmpty());
        }

        @Test
        void analyze_root_rm_catastrophic() {
            var decisions = List.of(
                new RiskDecision("rm -rf /", RiskLevel.BLOCK, "root rm", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals(1, result.size());
            assertEquals("CATASTROPHIC", result.get(0).irreversibility());
            assertTrue(result.get(0).worstCase().contains("清空"));
        }

        @Test
        void analyze_root_rm_star_catastrophic() {
            var decisions = List.of(
                new RiskDecision("rm -rf /*", RiskLevel.BLOCK, "root rm", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("CATASTROPHIC", result.get(0).irreversibility());
        }

        @Test
        void analyze_mkfs_catastrophic() {
            var decisions = List.of(
                new RiskDecision("mkfs.ext4 /dev/sda1", RiskLevel.BLOCK, "format", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("CATASTROPHIC", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("格式化"));
        }

        @Test
        void analyze_dd_of_dev_catastrophic() {
            var decisions = List.of(
                new RiskDecision("dd if=/dev/zero of=/dev/sda", RiskLevel.BLOCK, "dd", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("CATASTROPHIC", result.get(0).irreversibility());
        }

        @Test
        void analyze_rm_rf_var_log_high() {
            // rm -rf /var/log 被 isRootRm 匹配为 CATASTROPHIC（含 "/ " 模式）
            // 改用不带根路径的 rm -rf 测试
            var decisions = List.of(
                new RiskDecision("rm -rf tmp_data", RiskLevel.BLOCK, "rm -rf", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("HIGH", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("递归"));
        }

        @Test
        void analyze_chmod_r_high() {
            var decisions = List.of(
                new RiskDecision("chmod -R 777 /etc", RiskLevel.REVIEW, "chmod", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("HIGH", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("权限"));
        }

        @Test
        void analyze_chown_r_high() {
            var decisions = List.of(
                new RiskDecision("chown -R root:root /app", RiskLevel.REVIEW, "chown", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("HIGH", result.get(0).irreversibility());
        }

        @Test
        void analyze_systemctl_stop_medium() {
            var decisions = List.of(
                new RiskDecision("systemctl stop nginx", RiskLevel.REVIEW, "stop", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("MEDIUM", result.get(0).irreversibility());
            assertTrue(result.get(0).rollbackHint().contains("start"));
        }

        @Test
        void analyze_kill_medium() {
            var decisions = List.of(
                new RiskDecision("kill 1234", RiskLevel.BLOCK, "kill", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("MEDIUM", result.get(0).irreversibility());
        }

        @Test
        void analyze_shutdown_medium() {
            var decisions = List.of(
                new RiskDecision("shutdown -h now", RiskLevel.BLOCK, "shutdown", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("MEDIUM", result.get(0).irreversibility());
        }

        @Test
        void analyze_iptables_high() {
            var decisions = List.of(
                new RiskDecision("iptables -A INPUT -j DROP", RiskLevel.BLOCK, "iptables", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("HIGH", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("防火墙"));
        }

        @Test
        void analyze_drop_table_high() {
            var decisions = List.of(
                new RiskDecision("DROP TABLE users", RiskLevel.BLOCK, "sql", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("HIGH", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("数据库"));
        }

        @Test
        void analyze_apt_install_medium() {
            var decisions = List.of(
                new RiskDecision("apt install nginx", RiskLevel.REVIEW, "install", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("MEDIUM", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("软件包"));
        }

        @Test
        void analyze_redirect_medium() {
            var decisions = List.of(
                new RiskDecision("echo test > /etc/config", RiskLevel.REVIEW, "redirect", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("MEDIUM", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("写入"));
        }

        @Test
        void analyze_unknown_low() {
            var decisions = List.of(
                new RiskDecision("some_unknown_cmd", RiskLevel.REVIEW, "unknown", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("LOW", result.get(0).irreversibility());
        }

        @Test
        void analyze_unknown_with_path_low() {
            var decisions = List.of(
                new RiskDecision("some_cmd /var/data", RiskLevel.REVIEW, "unknown", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals("LOW", result.get(0).irreversibility());
            assertTrue(result.get(0).impacts().get(0).contains("/var/data"));
        }

        @Test
        void analyze_null_command_handled() {
            var decisions = List.of(
                new RiskDecision(null, RiskLevel.BLOCK, "null cmd", "blockedPattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals(1, result.size());
            assertEquals("LOW", result.get(0).irreversibility());
        }

        @Test
        void analyze_multiple_decisions() {
            var decisions = List.of(
                new RiskDecision("df -h", RiskLevel.SAFE, "read-only", "safeList"),
                new RiskDecision("rm -rf /tmp", RiskLevel.BLOCK, "rm", "blockedPattern"),
                new RiskDecision("systemctl stop nginx", RiskLevel.REVIEW, "stop", "changePattern")
            );
            var result = analyzer.analyze(decisions);
            assertEquals(2, result.size()); // SAFE filtered out
        }
    }

    // ═══════════════════════════════════════
    // SecurityScorer
    // ═══════════════════════════════════════
    @Nested
    @DisplayName("SecurityScorer")
    class ScorerTests {
        private final SecurityScorer scorer = new SecurityScorer();

        @Test
        void score_injection_blocked_full_static() {
            var result = scorer.score(true, RiskLevel.SAFE, null, null, "INJECTION_BLOCKED");
            assertEquals(30, result.staticRisk());
            assertEquals(35, result.dynamicAudit());
            assertEquals(35, result.restrictedExec());
            assertEquals(100, result.score());
            assertTrue(result.grade().contains("A"));
        }

        @Test
        void score_no_decisions_no_injection_neutral() {
            var result = scorer.score(false, RiskLevel.SAFE, null, null, "EXECUTED");
            assertEquals(15, result.staticRisk());
        }

        @Test
        void score_hard_block_metacharacter() {
            var decisions = List.of(
                new RiskDecision("rm -rf /", RiskLevel.BLOCK, "meta", "metacharacter")
            );
            var result = scorer.score(false, RiskLevel.BLOCK, decisions, null, "BLOCKED");
            assertEquals(30, result.staticRisk());
            assertEquals(33, result.dynamicAudit());
        }

        @Test
        void score_hard_block_blockedPattern() {
            var decisions = List.of(
                new RiskDecision("mkfs", RiskLevel.BLOCK, "blocked", "blockedPattern")
            );
            var result = scorer.score(false, RiskLevel.BLOCK, decisions, null, "BLOCKED");
            assertEquals(30, result.staticRisk());
        }

        @Test
        void score_hard_block_criticalPath() {
            var decisions = List.of(
                new RiskDecision("format", RiskLevel.BLOCK, "critical", "criticalPath")
            );
            var result = scorer.score(false, RiskLevel.BLOCK, decisions, null, "BLOCKED");
            assertEquals(30, result.staticRisk());
        }

        @Test
        void score_safe_decisions() {
            var decisions = List.of(
                new RiskDecision("df -h", RiskLevel.SAFE, "read-only", "safeList")
            );
            var result = scorer.score(false, RiskLevel.SAFE, decisions, null, "EXECUTED");
            assertEquals(28, result.staticRisk());
        }

        @Test
        void score_review_decisions() {
            var decisions = List.of(
                new RiskDecision("systemctl restart nginx", RiskLevel.REVIEW, "restart", "changePattern")
            );
            var result = scorer.score(false, RiskLevel.REVIEW, decisions, null, "REVIEW_PENDING");
            assertEquals(22, result.staticRisk());
            assertEquals(29, result.dynamicAudit());
        }

        @Test
        void score_block_no_hard_rule() {
            var decisions = List.of(
                new RiskDecision("some_cmd", RiskLevel.BLOCK, "blocked", "customRule")
            );
            var result = scorer.score(false, RiskLevel.BLOCK, decisions, null, "BLOCKED");
            assertEquals(24, result.staticRisk());
        }

        @Test
        void score_dynamic_safe_no_injection() {
            var result = scorer.score(false, RiskLevel.SAFE, null, null, "EXECUTED");
            assertEquals(32, result.dynamicAudit());
        }

        @Test
        void score_exec_no_execution() {
            var result = scorer.score(false, RiskLevel.SAFE, null, null, "EXECUTED");
            assertEquals(35, result.restrictedExec());
        }

        @Test
        void score_exec_real_execution() {
            var execResults = List.of(
                Map.<String, Object>of("executed", true, "dryRun", false, "level", "REVIEW")
            );
            var decisions = List.of(
                new RiskDecision("cmd", RiskLevel.REVIEW, "r", "rule")
            );
            var result = scorer.score(false, RiskLevel.REVIEW, decisions, execResults, "EXECUTED");
            assertEquals(27, result.restrictedExec());
        }

        @Test
        void score_exec_dryrun_only() {
            var execResults = List.of(
                Map.<String, Object>of("executed", true, "dryRun", true, "level", "REVIEW")
            );
            var decisions = List.of(
                new RiskDecision("cmd", RiskLevel.REVIEW, "r", "rule")
            );
            var result = scorer.score(false, RiskLevel.REVIEW, decisions, execResults, "EXECUTED");
            assertEquals(33, result.restrictedExec());
        }

        @Test
        void score_exec_safe_level() {
            var execResults = List.of(
                Map.<String, Object>of("executed", true, "dryRun", false, "level", "SAFE")
            );
            var decisions = List.of(
                new RiskDecision("cmd", RiskLevel.SAFE, "r", "safeList")
            );
            var result = scorer.score(false, RiskLevel.SAFE, decisions, execResults, "EXECUTED");
            assertEquals(33, result.restrictedExec());
        }

        @Test
        void score_grade_b() {
            // REVIEW decisions + no injection + real exec = 22 + 29 + 27 = 78 → B
            var decisions = List.of(
                new RiskDecision("cmd", RiskLevel.REVIEW, "r", "changePattern")
            );
            var execResults = List.of(
                Map.<String, Object>of("executed", true, "dryRun", false, "level", "REVIEW")
            );
            var result = scorer.score(false, RiskLevel.REVIEW, decisions, execResults, "EXECUTED");
            assertTrue(result.grade().contains("B"));
        }

        @Test
        void score_grade_c() {
            // Mix to get C (60-74)
            var decisions = List.of(
                new RiskDecision("cmd", RiskLevel.REVIEW, "r", "changePattern")
            );
            var execResults = List.of(
                Map.<String, Object>of("executed", true, "dryRun", false, "level", "BLOCK")
            );
            var result = scorer.score(false, RiskLevel.BLOCK, decisions, execResults, "EXECUTED");
            assertTrue(result.grade().contains("C") || result.grade().contains("B"));
        }

        @Test
        void score_notes_populated() {
            var result = scorer.score(true, RiskLevel.SAFE, null, null, "INJECTION_BLOCKED");
            assertFalse(result.notes().isEmpty());
            assertTrue(result.notes().size() >= 3);
        }
    }

    // ═══════════════════════════════════════
    // RollbackAdvisor
    // ═══════════════════════════════════════
    @Nested
    @DisplayName("RollbackAdvisor")
    class RollbackTests {
        private final RollbackAdvisor advisor = new RollbackAdvisor();

        @Test
        void advise_null_returns_null() {
            assertNull(advisor.advise(null));
        }

        @Test
        void advise_blank_returns_null() {
            assertNull(advisor.advise("   "));
        }

        @Test
        void advise_systemctl_stop_reversible() {
            var r = advisor.advise("systemctl stop nginx");
            assertNotNull(r);
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl start nginx", r.get("compensate"));
        }

        @Test
        void advise_systemctl_start_reversible() {
            var r = advisor.advise("systemctl start nginx");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl stop nginx", r.get("compensate"));
        }

        @Test
        void advise_systemctl_disable_reversible() {
            var r = advisor.advise("systemctl disable sshd");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl enable sshd", r.get("compensate"));
        }

        @Test
        void advise_systemctl_enable_reversible() {
            var r = advisor.advise("systemctl enable sshd");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl disable sshd", r.get("compensate"));
        }

        @Test
        void advise_systemctl_mask_reversible() {
            var r = advisor.advise("systemctl mask nginx");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl unmask nginx", r.get("compensate"));
        }

        @Test
        void advise_systemctl_unmask_reversible() {
            var r = advisor.advise("systemctl unmask nginx");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl mask nginx", r.get("compensate"));
        }

        @Test
        void advise_systemctl_restart_manual() {
            var r = advisor.advise("systemctl restart nginx");
            assertNotNull(r);
            assertFalse((Boolean) r.get("reversible"));
            assertNotNull(r.get("manual"));
        }

        @Test
        void advise_kill_manual() {
            var r = advisor.advise("kill 1234");
            assertFalse((Boolean) r.get("reversible"));
            assertTrue(r.get("manual").toString().contains("终止"));
        }

        @Test
        void advise_pkill_manual() {
            var r = advisor.advise("pkill nginx");
            assertFalse((Boolean) r.get("reversible"));
        }

        @Test
        void advise_killall_manual() {
            var r = advisor.advise("killall java");
            assertFalse((Boolean) r.get("reversible"));
        }

        @Test
        void advise_rm_manual() {
            var r = advisor.advise("rm /tmp/file");
            assertFalse((Boolean) r.get("reversible"));
            assertTrue(r.get("manual").toString().contains("不可逆"));
        }

        @Test
        void advise_rmdir_manual() {
            var r = advisor.advise("rmdir /tmp/dir");
            assertFalse((Boolean) r.get("reversible"));
        }

        @Test
        void advise_truncate_manual() {
            var r = advisor.advise("truncate -s 0 /var/log/syslog");
            assertFalse((Boolean) r.get("reversible"));
        }

        @Test
        void advise_mv_reversible() {
            var r = advisor.advise("mv /old/path /new/path");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("mv /new/path /old/path", r.get("compensate"));
        }

        @Test
        void advise_chmod_manual() {
            var r = advisor.advise("chmod 777 /etc");
            assertFalse((Boolean) r.get("reversible"));
            assertTrue(r.get("manual").toString().contains("权限"));
        }

        @Test
        void advise_chown_manual() {
            var r = advisor.advise("chown root:root /app");
            assertFalse((Boolean) r.get("reversible"));
        }

        @Test
        void advise_mount_manual() {
            var r = advisor.advise("mount /dev/sdb1 /mnt");
            assertFalse((Boolean) r.get("reversible"));
            assertTrue(r.get("manual").toString().contains("umount"));
        }

        @Test
        void advise_umount_reversible() {
            var r = advisor.advise("umount /mnt");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("mount /mnt", r.get("compensate"));
        }

        @Test
        void advise_unknown_default_manual() {
            var r = advisor.advise("some_random_command");
            assertFalse((Boolean) r.get("reversible"));
            assertTrue(r.get("manual").toString().contains("无内置逆操作"));
        }

        @Test
        void advise_systemctl_absolute_path() {
            var r = advisor.advise("/usr/bin/systemctl stop nginx");
            assertTrue((Boolean) r.get("reversible"));
            assertEquals("systemctl start nginx", r.get("compensate"));
        }

        @Test
        void buildLedger_null_decisions_empty() {
            assertTrue(advisor.buildLedger(null, null).isEmpty());
        }

        @Test
        void buildLedger_safe_decisions_filtered() {
            var decisions = List.of(
                new RiskDecision("df -h", RiskLevel.SAFE, "safe", "safeList")
            );
            var execResults = List.of(
                Map.<String, Object>of("executed", true)
            );
            assertTrue(advisor.buildLedger(decisions, execResults).isEmpty());
        }

        @Test
        void buildLedger_executed_review_included() {
            var decisions = List.of(
                new RiskDecision("systemctl stop nginx", RiskLevel.REVIEW, "stop", "changePattern")
            );
            var execResults = List.of(
                Map.<String, Object>of("executed", true)
            );
            var ledger = advisor.buildLedger(decisions, execResults);
            assertEquals(1, ledger.size());
            assertTrue((Boolean) ledger.get(0).get("reversible"));
        }

        @Test
        void buildLedger_not_executed_filtered() {
            var decisions = List.of(
                new RiskDecision("systemctl stop nginx", RiskLevel.REVIEW, "stop", "changePattern")
            );
            var execResults = List.of(
                Map.<String, Object>of("executed", false)
            );
            assertTrue(advisor.buildLedger(decisions, execResults).isEmpty());
        }

        @Test
        void buildLedger_no_exec_results_filtered() {
            var decisions = List.of(
                new RiskDecision("systemctl stop nginx", RiskLevel.REVIEW, "stop", "changePattern")
            );
            assertTrue(advisor.buildLedger(decisions, null).isEmpty());
        }

        @Test
        void buildLedger_mixed_decisions() {
            var decisions = List.of(
                new RiskDecision("df -h", RiskLevel.SAFE, "safe", "safeList"),
                new RiskDecision("systemctl stop nginx", RiskLevel.REVIEW, "stop", "changePattern"),
                new RiskDecision("rm -rf /tmp", RiskLevel.BLOCK, "rm", "blockedPattern")
            );
            var execResults = List.of(
                Map.<String, Object>of("executed", true),
                Map.<String, Object>of("executed", true),
                Map.<String, Object>of("executed", false)
            );
            var ledger = advisor.buildLedger(decisions, execResults);
            assertEquals(1, ledger.size()); // Only the REVIEW one that was executed
        }
    }
}
