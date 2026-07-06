package com.zhiqian.ops.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 断点续跑：回滚账本落盘后，新进程实例（模拟重启）仍可读到账本。 */
class RollbackLedgerPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void ledger_survives_restart_via_jsonl_persistence() {
        Path file = tempDir.resolve("rollback-ledger.jsonl");
        RollbackLedger before = new RollbackLedger(file);
        before.record("trace-persist-1", List.of(Map.of(
                "origin", "systemctl stop nginx",
                "reversible", true,
                "compensate", "systemctl start nginx")));
        assertTrue(Files.exists(file));

        // 模拟服务重启：同一落盘文件构造新实例
        RollbackLedger after = new RollbackLedger(file);
        assertTrue(after.has("trace-persist-1"));
        assertEquals("systemctl start nginx", after.get("trace-persist-1").get(0).get("compensate"));
    }

    @Test
    void corrupted_lines_are_skipped_without_breaking_load() throws Exception {
        Path file = tempDir.resolve("rollback-ledger.jsonl");
        RollbackLedger before = new RollbackLedger(file);
        before.record("trace-ok", List.of(Map.of("origin", "mv /a /b", "compensate", "mv /b /a")));
        Files.writeString(file, Files.readString(file) + "{not-json\n");

        RollbackLedger after = new RollbackLedger(file);
        assertTrue(after.has("trace-ok"));
    }

    @Test
    void no_arg_constructor_stays_pure_in_memory() {
        RollbackLedger memOnly = new RollbackLedger();
        memOnly.record("trace-mem", List.of(Map.of("origin", "x")));
        assertTrue(memOnly.has("trace-mem"));
        // 新的纯内存实例自然为空（无任何落盘介质）
        assertFalse(new RollbackLedger().has("trace-mem"));
    }
}
