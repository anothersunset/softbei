package com.zhiqian.ops.guard;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRiskGuardCommandSurfaceTest {

    private static IntentRiskGuard guard;

    @BeforeAll
    static void setUp() throws Exception {
        guard = new IntentRiskGuard(new RiskRuleLoader());
    }

    @Test
    void find_delete_and_exec_are_blocked_but_plain_find_stays_safe() {
        assertEquals(RiskLevel.BLOCK, guard.evaluate("find /data -type f -delete").level());
        assertEquals(RiskLevel.BLOCK, guard.evaluate("find /tmp -name '*.log' -exec rm {}").level());
        assertEquals(RiskLevel.BLOCK, guard.evaluate("find /tmp -name '*.log' -fprint /tmp/out.txt").level());

        RiskDecision safe = guard.evaluate("find /var/log -name '*.log'");
        assertEquals(RiskLevel.READONLY, safe.level());
    }

    @Test
    void container_and_kubernetes_subcommands_are_classified_by_intent() {
        assertEquals(RiskLevel.READONLY, guard.evaluate("docker ps").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("docker rm -f payment-svc").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("docker restart payment-svc").level());

        assertEquals(RiskLevel.READONLY, guard.evaluate("kubectl get pods -n prod").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("kubectl delete pod api-0 -n prod").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("kubectl scale deployment api --replicas=0").level());
    }

    @Test
    void critical_service_changes_are_upgraded_to_irreversible() {
        RiskDecision restartNginx = guard.evaluate("systemctl restart nginx");

        assertEquals(RiskLevel.IRREVERSIBLE, restartNginx.level());
        assertEquals("criticalService", restartNginx.matchedRule());
        assertEquals(true, restartNginx.requiresApproval());
        assertEquals(true, restartNginx.requiresBackup());
        assertEquals(true, restartNginx.requiresDryRun());
    }

    @Test
    void cron_sed_tar_and_rsync_keep_readonly_paths_safe_and_writes_reviewed() {
        assertEquals(RiskLevel.READONLY, guard.evaluate("crontab -l").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("crontab -r").level());

        assertEquals(RiskLevel.READONLY, guard.evaluate("sed 's/error/warn/' /var/log/app.log").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("sed -i 's/debug/info/' /tmp/app.conf").level());
        assertEquals(RiskLevel.IRREVERSIBLE, guard.evaluate("sed -i 's/root/admin/' /etc/passwd").level());

        assertEquals(RiskLevel.READONLY, guard.evaluate("tar tvf app.tar").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("tar xzf app.tar -C /tmp/app").level());
        assertEquals(RiskLevel.IRREVERSIBLE, guard.evaluate("tar xzf app.tar -C /etc").level());

        assertEquals(RiskLevel.READONLY, guard.evaluate("rsync --dry-run -av ./dist/ /tmp/dist/").level());
        assertEquals(RiskLevel.EXECUTABLE, guard.evaluate("rsync -av ./dist/ /tmp/dist/").level());
        assertEquals(RiskLevel.IRREVERSIBLE, guard.evaluate("rsync -av ./dist/ /etc/").level());
    }
}
