package com.zhiqian.ops.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 反事实回放分析器：对被 BLOCK / EXECUTABLE / IRREVERSIBLE 的命令做纯静态分析，
 * 推演「若放行会发生什么」，给出不可逆等级、影响范围、最坏后果与回滚建议。
 * 全程不执行任何命令，零副作用。
 */
public class CounterfactualAnalyzer {

    public List<ImpactEstimate> analyze(List<RiskDecision> decisions) {
        List<ImpactEstimate> out = new ArrayList<>();
        if (decisions == null) {
            return out;
        }
        for (RiskDecision d : decisions) {
            if (d.level() == RiskLevel.BLOCK || d.level().requiresApproval()) {
                out.add(estimate(d));
            }
        }
        return out;
    }

    private ImpactEstimate estimate(RiskDecision d) {
        String cmd = d.command() == null ? "" : d.command();
        String low = cmd.toLowerCase(Locale.ROOT);
        List<String> impacts = new ArrayList<>();
        List<String> paths = extractPaths(cmd);

        if (isRootRm(low)) {
            impacts.add("递归删除根目录下的全部文件");
            return new ImpactEstimate(cmd, d.level(), "CATASTROPHIC", impacts,
                    "整个系统文件被清空，主机立即不可用且无法正常重启",
                    "无法软回滚：只能依赖整机快照/备份重装恢复");
        }
        if (low.contains("mkfs") || (low.contains("dd ") && low.contains("of=/dev/"))
                || low.contains("fdisk") || low.contains("parted") || low.contains("wipefs")) {
            impacts.add("对磁盘/分区进行格式化或低层覆写");
            return new ImpactEstimate(cmd, d.level(), "CATASTROPHIC", impacts,
                    "目标磁盘数据被全部覆盖，原有数据不可恢复",
                    "无法软回滚：需从离线备份恢复数据");
        }
        if (low.contains("rm ") && (low.contains("-r") || low.contains("-f"))) {
            impacts.add("递归/强制删除：" + (paths.isEmpty() ? "指定目录" : String.join(", ", paths)));
            return new ImpactEstimate(cmd, d.level(), "HIGH", impacts,
                    "目标目录及子文件被删除，相关服务可能因缺文件而异常",
                    "建议先 tar 备份目标目录，或从快照/版本库恢复被删文件");
        }
        if ((low.contains("chmod") || low.contains("chown")) && low.contains("-r")) {
            impacts.add("递归修改权限/属主：" + (paths.isEmpty() ? "目标路径" : String.join(", ", paths)));
            return new ImpactEstimate(cmd, d.level(), "HIGH", impacts,
                    "大范围权限变更可能导致服务无法读写、登录异常",
                    "记录原 stat 权限位，回滚时按原值重新 chmod/chown");
        }
        if (low.contains("systemctl stop") || low.contains("systemctl disable")
                || low.startsWith("kill ") || low.contains("killall") || low.contains("pkill")
                || low.contains("shutdown") || low.contains("reboot")
                || low.contains("poweroff") || low.contains("halt")) {
            impacts.add("停止服务/进程或重启主机");
            return new ImpactEstimate(cmd, d.level(), "MEDIUM", impacts,
                    "目标服务中断，相关业务在恢复前不可用",
                    "可回滚：重新 systemctl start 或重启对应服务即可恢复");
        }
        if (low.contains("iptables") || low.contains("firewall-cmd") || low.contains("nft ")
                || low.contains("ip route") || low.contains("ifconfig") || low.contains("ip link")) {
            impacts.add("修改防火墙/网络配置");
            return new ImpactEstimate(cmd, d.level(), "HIGH", impacts,
                    "网络策略变更可能导致远程连接中断、主机失联",
                    "可回滚：先 iptables-save 备份规则，失联时经带外/控制台恢复");
        }
        if (low.contains("drop ") || low.contains("truncate") || low.contains("delete from")) {
            impacts.add("删除数据库表/记录");
            return new ImpactEstimate(cmd, d.level(), "HIGH", impacts,
                    "数据被删除，依赖该数据的业务将报错或丢失记录",
                    "可回滚：需提前 mysqldump/逻辑备份，或从 binlog/PITR 恢复");
        }
        if (low.contains("apt ") || low.contains("yum ") || low.contains("dnf ")
                || low.contains("rpm ") || low.contains("pip install") || low.contains("npm install")) {
            impacts.add("安装/卸载软件包，改变系统依赖");
            return new ImpactEstimate(cmd, d.level(), "MEDIUM", impacts,
                    "依赖变更可能引入不兼容，影响相关服务运行",
                    "可回滚：记录变更前版本，必要时降级/卸载还原");
        }
        if (cmd.contains(">") || low.startsWith("tee") || low.contains("sed -i")) {
            impacts.add("写入/覆盖文件内容：" + (paths.isEmpty() ? "目标文件" : String.join(", ", paths)));
            return new ImpactEstimate(cmd, d.level(), "MEDIUM", impacts,
                    "目标文件被改写，配置错误可能导致服务启动失败",
                    "可回滚：写入前备份原文件(.bak)，回滚时覆盖还原");
        }

        if (paths.isEmpty()) {
            impacts.add("命令意图为变更类，影响范围需进一步评估");
        } else {
            impacts.add("可能影响：" + String.join(", ", paths));
        }
        return new ImpactEstimate(cmd, d.level(), "LOW", impacts,
                "潜在影响有限，但因不在只读白名单内，放行前应人工核实",
                "建议在 dry-run/沙箱中先行验证，确认无副作用后再执行");
    }

    private boolean isRootRm(String low) {
        if (!low.contains("rm")) {
            return false;
        }
        boolean rf = low.contains("-rf") || low.contains("-fr")
                || (low.contains("-r") && low.contains("-f")) || low.contains("--recursive");
        String t = low.trim();
        boolean targetsRoot = t.endsWith(" /") || low.contains(" /*")
                || low.contains(" / ") || low.contains("rm -rf /") || low.contains("rm -fr /");
        return rf && targetsRoot;
    }

    private List<String> extractPaths(String cmd) {
        List<String> paths = new ArrayList<>();
        for (String tok : cmd.split(" ")) {
            String t = tok.trim();
            if (!t.isEmpty() && (t.charAt(0) == '\'' || t.charAt(0) == '"')) {
                t = t.substring(1);
            }
            if (!t.isEmpty() && (t.charAt(t.length() - 1) == '\'' || t.charAt(t.length() - 1) == '"')) {
                t = t.substring(0, t.length() - 1);
            }
            if (t.startsWith("/") && t.length() > 1) {
                paths.add(t);
            }
        }
        return paths;
    }
}
