package com.zhiqian.ops.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreChangeBackupTest {

    @TempDir
    Path tempDir;

    private PreChangeBackup backupWithDir(Path dir) {
        ExecProperties props = new ExecProperties();
        props.setBackupDir(dir.toString());
        return new PreChangeBackup(props);
    }

    @Test
    void backs_up_existing_target_file_before_mutation() throws Exception {
        Path target = tempDir.resolve("app.conf");
        Files.writeString(target, "listen 8080\n");
        PreChangeBackup backup = backupWithDir(tempDir.resolve("backups"));

        List<Map<String, Object>> records =
                backup.backup("trace-1", List.of("rm", "-f", target.toString()));

        assertEquals(1, records.size());
        Map<String, Object> r = records.get(0);
        assertEquals(target.toString(), r.get("origin"));
        Path copied = Path.of(String.valueOf(r.get("backup")));
        assertTrue(Files.exists(copied));
        assertEquals("listen 8080\n", Files.readString(copied));
        // 备份落在 <backupDir>/<traceId>/ 下
        assertTrue(copied.toString().contains("trace-1"));
    }

    @Test
    void handles_dd_style_of_argument() throws Exception {
        Path target = tempDir.resolve("disk.img");
        Files.writeString(target, "data");
        PreChangeBackup backup = backupWithDir(tempDir.resolve("backups"));

        List<Map<String, Object>> records =
                backup.backup("trace-2", List.of("dd", "if=/dev/zero", "of=" + target));

        assertEquals(1, records.size());
        assertEquals(target.toString(), records.get(0).get("origin"));
        assertTrue(Files.exists(Path.of(String.valueOf(records.get(0).get("backup")))));
    }

    @Test
    void skips_directories_and_ignores_missing_or_relative_paths() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("data"));
        PreChangeBackup backup = backupWithDir(tempDir.resolve("backups"));

        List<Map<String, Object>> records = backup.backup("trace-3", List.of(
                "rm", "-rf",
                dir.toString(),                          // 目录：留痕但不复制
                tempDir.resolve("ghost.log").toString(), // 不存在：完全跳过
                "relative/path.txt"));                   // 相对路径：不是备份对象

        assertEquals(1, records.size());
        assertEquals(dir.toString(), records.get(0).get("origin"));
        assertTrue(String.valueOf(records.get(0).get("skipped")).contains("非常规文件"));
    }

    @Test
    void returns_empty_for_blank_trace_or_bare_command() throws Exception {
        PreChangeBackup backup = backupWithDir(tempDir.resolve("backups"));
        assertTrue(backup.backup(null, List.of("rm", "/tmp/x")).isEmpty());
        assertTrue(backup.backup("t", List.of("rm")).isEmpty());
        assertTrue(backup.backup("t", null).isEmpty());
    }
}
