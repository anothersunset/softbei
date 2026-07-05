package com.zhiqian.ops.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 Mock 推理客户端：无需 API Key 即可演示全链路。
 * 根据指令关键词路由到不同的运维计划，并原样回显用户显式下达的命令，
 * 以便安全护栏能对高危/越权指令做出拦截演示。
 */
public class MockLlmClient implements LlmClient {
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern EXPLICIT_CMD = Pattern.compile(
            "(?i)\\b(rm|rmdir|chmod|chown|chgrp|kill|killall|pkill|dd|mkfs|systemctl|service|mv|truncate|tee|mount|umount|iptables|nft|useradd|userdel|passwd)\\b[^\\n]*");

    @Override
    public boolean isReal() { return false; }

    @Override
    public String providerName() { return "mock"; }

    @Override
    public String chat(String prompt) {
        String instruction = extractInstruction(prompt);
        String lc = instruction.toLowerCase();
        PlanResult r = new PlanResult();
        List<PlanStep> steps = new ArrayList<>();

        String explicit = detectExplicitCommand(instruction);
        String criticalDelete = detectCriticalDelete(instruction);
        String pipeCommand = detectPipeCommand(instruction);

        if (criticalDelete != null) {
            // 演示误删关键数据目录 -> 预期被护栏 BLOCK
            steps.add(new PlanStep(criticalDelete, "用户要求删除目标路径下的内容"));
            r.setSummary("检测到针对关键路径的删除意图，已生成计划交由安全护栏校验");
            r.setRootCauseHypothesis("用户显式要求删除关键路径");
            r.setConfidence(0.4);
        } else if (pipeCommand != null) {
            // 显式管道命令原样回显，交由护栏做分段裁决（ps|grep 放行 / |tee 需确认 / |sh 红线）。
            // 关键：不回显则护栏看不到管道命令，会误判为安全默认计划 -> 演示/验收失真。
            steps.add(new PlanStep(pipeCommand, "用户直接下达的管道命令"));
            r.setSummary("检测到显式管道命令，已交由安全护栏分段裁决");
            r.setRootCauseHypothesis("用户显式指定管道操作");
            r.setConfidence(0.5);
        } else if (explicit != null) {
            steps.add(new PlanStep(explicit, "用户直接下达的运维指令"));
            r.setSummary("检测到显式运维指令，已交由安全护栏校验");
            r.setRootCauseHypothesis("用户显式指定操作");
            r.setConfidence(0.5);
        } else if (lc.contains("清理") || lc.contains("垃圾") || lc.contains("磁盘") || lc.contains("空间") || lc.contains("满") || lc.contains("disk")) {
            steps.add(new PlanStep("df -h", "查看各文件系统使用率，确认告警分区"));
            steps.add(new PlanStep("du -h --max-depth=1 /var/log", "定位 /var/log 下占用最大的目录"));
            steps.add(new PlanStep("rm -f /var/log/app/app.log.1", "删除已轮转的历史日志以释放空间"));
            r.setSummary("磁盘空间告急，拟定位并清理已轮转日志");
            r.setRootCauseHypothesis("/var/log 下日志未及时轮转，占满磁盘分区");
            r.setConfidence(0.82);
        } else if (lc.contains("僵尸") || lc.contains("zombie") || lc.contains("defunct")) {
            steps.add(new PlanStep("ps -el", "列出进程状态，查找状态为 Z 的僵尸进程"));
            steps.add(new PlanStep("ps -o pid,ppid,stat,cmd --ppid 1", "定位僵尸进程的父进程"));
            steps.add(new PlanStep("kill -CHLD 4321", "向父进程发送 SIGCHLD，促其回收僵尸子进程"));
            r.setSummary("存在僵尸进程，拟定位父进程并促其回收");
            r.setRootCauseHypothesis("父进程未正确 wait() 回收子进程");
            r.setConfidence(0.7);
        } else if (lc.contains("端口") || lc.contains("网络") || lc.contains("连接") || lc.contains("port") || lc.contains("network")) {
            steps.add(new PlanStep("ss -tnlp", "查看监听中的端口及对应进程"));
            steps.add(new PlanStep("ss -s", "统计各状态连接数量"));
            steps.add(new PlanStep("netstat -i", "查看网卡收发包与错误统计"));
            r.setSummary("拟从监听端口、连接状态、网卡统计三个维度排查网络问题");
            r.setRootCauseHypothesis("待确认：可能为端口未监听或连接耗尽");
            r.setConfidence(0.6);
        } else if (lc.contains("日志") || lc.contains("报错") || lc.contains("错误") || lc.contains("error") || lc.contains("log")) {
            steps.add(new PlanStep("journalctl -p err -n 200 --no-pager", "拉取最近 200 条错误级别系统日志"));
            steps.add(new PlanStep("tail -n 100 /var/log/messages", "查看系统日志尾部"));
            r.setSummary("拟从 systemd 日志与系统日志中检索错误线索");
            r.setRootCauseHypothesis("待日志证据确认具体报错原因");
            r.setConfidence(0.65);
        } else {
            steps.add(new PlanStep("uptime", "查看系统负载与运行时长"));
            steps.add(new PlanStep("free -h", "查看内存使用情况"));
            steps.add(new PlanStep("df -h", "查看磁盘使用情况"));
            r.setSummary("未识别到明确意图，先采集系统基线指标供进一步判断");
            r.setRootCauseHypothesis("信息不足，需先感知环境");
            r.setConfidence(0.5);
        }

        r.setSteps(steps);
        try {
            return mapper.writeValueAsString(r);
        } catch (Exception e) {
            return "{\"summary\":\"mock error\",\"steps\":[]}";
        }
    }

    private String extractInstruction(String prompt) {
        if (prompt == null) return "";
        Matcher m = Pattern.compile("INSTRUCTION:\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(prompt);
        if (m.find()) {
            return m.group(1).trim();
        }
        return prompt;
    }

    private String detectExplicitCommand(String instruction) {
        Matcher m = EXPLICIT_CMD.matcher(instruction);
        if (m.find()) {
            return m.group().trim();
        }
        return null;
    }

    /**
     * 检测用户显式下达的 shell 管道命令并原样回显，供护栏分段裁决。
     * 仅当剥离「执行/运行/run」前缀后仍含单个管道 {@code |}（非 {@code ||}）且以命令样式开头时触发，
     * 避免把「执行一次健康体检」这类自然语言误当成命令。
     */
    private String detectPipeCommand(String instruction) {
        if (instruction == null) return null;
        String s = instruction.replaceAll("^\\s*(请)?(帮我)?(执行|运行|run)\\s*[:：]?\\s*", "").trim();
        boolean hasPipe = s.contains("|") && !s.contains("||");
        if (hasPipe && s.matches("^[A-Za-z/][\\w./-]*\\s.*")) {
            return s;
        }
        return null;
    }

    private String detectCriticalDelete(String instruction) {
        String lc = instruction.toLowerCase();
        boolean deleteIntent = lc.contains("删") || lc.contains("清空") || lc.contains("drop") || lc.contains("rm ");
        if (!deleteIntent) return null;
        String[] criticalHints = {"/var/lib/mysql", "/var/lib/pgsql", "/etc", "/boot", "/data"};
        for (String p : criticalHints) {
            if (instruction.contains(p)) {
                return "rm -rf " + p;
            }
        }
        return null;
    }
}
