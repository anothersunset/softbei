package com.zhiqian.ops.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行前自动备份：变更类命令真实执行前，把命中的目标文件复制到备份目录，
 * 供回滚账本生成「从备份恢复」的补偿指令（对齐"受限执行环境：执行前自动备份+一键回滚"要求）。
 * 仅备份常规文件（目录/设备文件不复制，超限文件跳过并留痕），失败不阻断执行主链路。
 */
public class PreChangeBackup {
    private static final Logger log = LoggerFactory.getLogger(PreChangeBackup.class);
    /** 单文件备份大小上限：避免复制超大日志拖垮执行链路。 */
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** 单条命令最多备份的文件数。 */
    private static final int MAX_FILES_PER_COMMAND = 5;

    private final ExecProperties props;

    public PreChangeBackup(ExecProperties props) {
        this.props = props;
    }

    /**
     * 扫描 argv 中的绝对路径实参，把存在的常规文件备份到 <backupDir>/<traceId>/ 下。
     * @return 备份记录列表；每条含 origin 与 backup（成功）或 skipped 原因，空列表表示无可备份目标。
     */
    public List<Map<String, Object>> backup(String traceId, List<String> argv) {
        List<Map<String, Object>> records = new ArrayList<>();
        if (traceId == null || traceId.isBlank() || argv == null || argv.size() < 2) {
            return records;
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (int i = 1; i < argv.size() && candidates.size() < MAX_FILES_PER_COMMAND; i++) {
            String tok = argv.get(i);
            if (tok == null) continue;
            // dd/形如 key=/path 的参数：只有「输出目标」才是变更对象，输入源不备份。
            // if=（输入文件）、bs=/count= 等非目标参数一律跳过；of= 取等号右侧作为目标。
            int eq = tok.indexOf('=');
            if (eq > 0) {
                String key = tok.substring(0, eq).toLowerCase();
                if (!key.equals("of") && !key.equals("output")) {
                    continue; // if=/bs=/count=/conv= 等：不是变更目标
                }
                tok = tok.substring(eq + 1);
            }
            // 设备/伪文件系统不是常规备份对象，直接排除，避免把 /dev/zero 之类计入
            if (tok.startsWith("/dev/") || tok.startsWith("/proc/") || tok.startsWith("/sys/")) {
                continue;
            }
            if (looksAbsolute(tok)) {
                candidates.add(tok);
            }
        }
        if (candidates.isEmpty()) {
            return records;
        }
        String safeTrace = traceId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dir = Path.of(backupDir()).resolve(safeTrace).normalize();
        int seq = 0;
        for (String candidate : candidates) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("origin", candidate);
            try {
                Path src = Path.of(candidate);
                if (!Files.exists(src)) {
                    continue; // 目标不存在（如 mv 目的地）：不是备份对象，也无需留痕
                }
                if (!Files.isRegularFile(src)) {
                    r.put("skipped", "非常规文件（目录/设备），不自动备份");
                    records.add(r);
                    continue;
                }
                long size = Files.size(src);
                if (size > MAX_FILE_BYTES) {
                    r.put("skipped", "文件 " + size + " bytes 超出备份上限，请人工快照");
                    records.add(r);
                    continue;
                }
                Files.createDirectories(dir);
                String fileName = src.getFileName() == null ? "file" : src.getFileName().toString();
                Path dst = dir.resolve((seq++) + "-" + fileName + ".bak");
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                r.put("backup", dst.toString());
                r.put("bytes", size);
                records.add(r);
            } catch (Exception e) {
                r.put("skipped", "备份失败：" + e.getMessage());
                records.add(r);
                log.warn("pre-change backup failed for {}: {}", candidate, e.getMessage());
            }
        }
        return records;
    }

    /** 绝对路径判断：Linux 目标环境为 /... 形式；兼容 Windows 盘符路径便于本地开发验证。 */
    private boolean looksAbsolute(String tok) {
        if (tok.startsWith("/")) return true;
        return tok.length() >= 3 && Character.isLetter(tok.charAt(0)) && tok.charAt(1) == ':'
                && (tok.charAt(2) == '/' || tok.charAt(2) == '\\');
    }

    private String backupDir() {
        String dir = props.getBackupDir();
        return dir == null || dir.isBlank() ? "logs/backups" : dir;
    }
}
