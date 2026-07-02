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
        assertEquals(RiskLevel.SAFE, safe.level());
    }

    @Test
    void container_and_kubernetes_subcommands_are_classified_by_intent() {
        assertEquals(RiskLevel.SAFE, guard.evaluate("docker ps").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("docker rm -f payment-svc").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("docker restart payment-svc").level());

        assertEquals(RiskLevel.SAFE, guard.evaluate("kubectl get pods -n prod").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("kubectl delete pod api-0 -n prod").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("kubectl scale deployment api --replicas=0").level());
    }

    @Test
    void cron_sed_tar_and_rsync_keep_readonly_paths_safe_and_writes_reviewed() {
        assertEquals(RiskLevel.SAFE, guard.evaluate("crontab -l").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("crontab -r").level());

        assertEquals(RiskLevel.SAFE, guard.evaluate("sed 's/error/warn/' /var/log/app.log").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("sed -i 's/debug/info/' /tmp/app.conf").level());
        assertEquals(RiskLevel.BLOCK, guard.evaluate("sed -i 's/root/admin/' /etc/passwd").level());

        assertEquals(RiskLevel.SAFE, guard.evaluate("tar tvf app.tar").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("tar xzf app.tar -C /tmp/app").level());
        assertEquals(RiskLevel.BLOCK, guard.evaluate("tar xzf app.tar -C /etc").level());

        assertEquals(RiskLevel.SAFE, guard.evaluate("rsync --dry-run -av ./dist/ /tmp/dist/").level());
        assertEquals(RiskLevel.REVIEW, guard.evaluate("rsync -av ./dist/ /tmp/dist/").level());
        assertEquals(RiskLevel.BLOCK, guard.evaluate("rsync -av ./dist/ /etc/").level());
    }
}
