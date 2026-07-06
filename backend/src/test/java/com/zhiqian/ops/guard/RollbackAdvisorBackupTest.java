package com.zhiqian.ops.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行前自动备份 -> 回滚账本「从备份恢复」补偿指令的联动。 */
class RollbackAdvisorBackupTest {

    @Test
    void ledger_contains_restore_entry_when_pre_backup_exists() {
        RollbackAdvisor advisor = new RollbackAdvisor();
        List<RiskDecision> decisions = List.of(
                new RiskDecision("rm -f /var/log/app/app.log.1", RiskLevel.EXECUTABLE, "change", "mutating"));
        List<Map<String, Object>> execResults = List.of(Map.of(
                "executed", true,
                "dryRun", false,
                "preBackup", List.of(Map.of(
                        "origin", "/var/log/app/app.log.1",
                        "backup", "logs/backups/t-1/0-app.log.1.bak",
                        "bytes", 128))));

        List<Map<String, Object>> ledger = advisor.buildLedger(decisions, execResults);

        // rm 本身的 manual 指引 + 备份恢复补偿，两条并存
        assertEquals(2, ledger.size());
        Map<String, Object> restore = ledger.get(1);
        assertEquals("restore-backup", restore.get("action"));
        assertEquals(Boolean.TRUE, restore.get("reversible"));
        assertEquals("cp logs/backups/t-1/0-app.log.1.bak /var/log/app/app.log.1",
                restore.get("compensate"));
    }

    @Test
    void skipped_backups_do_not_produce_restore_entries() {
        RollbackAdvisor advisor = new RollbackAdvisor();
        List<RiskDecision> decisions = List.of(
                new RiskDecision("rm -rf /data/cache", RiskLevel.EXECUTABLE, "change", "mutating"));
        List<Map<String, Object>> execResults = List.of(Map.of(
                "executed", true,
                "preBackup", List.of(Map.of(
                        "origin", "/data/cache",
                        "skipped", "非常规文件（目录/设备），不自动备份"))));

        List<Map<String, Object>> ledger = advisor.buildLedger(decisions, execResults);

        assertEquals(1, ledger.size());
        assertTrue(String.valueOf(ledger.get(0).get("manual")).contains("备份或快照"));
    }
}
