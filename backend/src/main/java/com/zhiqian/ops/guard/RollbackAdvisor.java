package com.zhiqian.ops.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回滚顾问：为已执行的变更类指令生成「最佳努力」的补偿/回滚指令与说明，形成可一键回滚的动作账本。
 * 仅生成非破坏性逆操作（如 systemctl start/enable/unmask、umount->mount、mv 还原）；
 * 对不可逆操作（rm、kill 等）给出人工恢复指引，不杜撰危险命令。
 */
public class RollbackAdvisor {

    /** 为单条命令生成回滚建议；返回 null 表示无需/无法给出建议。 */
    public Map<String, Object> advise(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String cmd = command.trim();
        String[] t = cmd.split("\\s+");
        String bin = t.length > 0 ? baseName(t[0]) : "";

        String compensate = null;
        String manual = null;
        boolean reversible = false;

        switch (bin) {
            case "systemctl" -> {
                if (t.length >= 3) {
                    String action = t[1];
                    String unit = t[2];
                    String inverse = switch (action) {
                        case "stop" -> "start";
                        case "start" -> "stop";
                        case "disable" -> "enable";
                        case "enable" -> "disable";
                        case "mask" -> "unmask";
                        case "unmask" -> "mask";
                        default -> null;
                    };
                    if (inverse != null) {
                        compensate = "systemctl " + inverse + " " + unit;
                        reversible = true;
                    } else if ("restart".equals(action)) {
                        manual = "restart 无幂等逆操作，如需回滚请依据变更前状态手动恢复服务 " + unit;
                    }
                }
            }
            case "kill", "pkill", "killall" -> manual = "进程已终止，无法原地回滚；请重启对应服务或由其守护进程拉起";
            case "rm", "rmdir", "truncate" -> manual = "删除/截断类操作不可逆，请从执行前备份或快照恢复对应文件/目录";
            case "mv" -> {
                if (t.length >= 3) {
                    compensate = "mv " + t[t.length - 1] + " " + t[t.length - 2];
                    reversible = true;
                }
            }
            case "chmod", "chown", "chgrp" -> manual = "权限/属主变更请依据执行前记录的原始权限恢复（建议执行前 stat 留存）";
            case "mount" -> manual = "如需回滚请 umount 对应挂载点并恢复 /etc/fstab";
            case "umount" -> {
                if (t.length >= 2) {
                    compensate = "mount " + t[t.length - 1];
                    reversible = true;
                }
            }
            default -> manual = "该变更无内置逆操作，回滚需依据执行前备份/快照人工恢复";
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("origin", cmd);
        r.put("reversible", reversible);
        if (compensate != null) {
            r.put("compensate", compensate);
        }
        if (manual != null) {
            r.put("manual", manual);
        }
        return r;
    }

    /** 为一组裁决生成动作账本：仅纳入「已执行的变更类」命令（READONLY 只读与未执行/BLOCK 不计）。 */
    public List<Map<String, Object>> buildLedger(List<RiskDecision> decisions, List<Map<String, Object>> execResults) {
        List<Map<String, Object>> ledger = new ArrayList<>();
        if (decisions == null) {
            return ledger;
        }
        for (int i = 0; i < decisions.size(); i++) {
            RiskDecision d = decisions.get(i);
            if (d.level() == RiskLevel.READONLY || d.level() == RiskLevel.BLOCK) {
                continue;
            }
            boolean executed = false;
            if (execResults != null && i < execResults.size()) {
                executed = Boolean.TRUE.equals(execResults.get(i).get("executed"));
            }
            if (!executed) {
                continue;
            }
            Map<String, Object> adv = advise(d.command());
            if (adv != null) {
                ledger.add(adv);
            }
        }
        return ledger;
    }

    private String baseName(String bin) {
        int idx = bin.lastIndexOf('/');
        return idx >= 0 ? bin.substring(idx + 1) : bin;
    }
}
