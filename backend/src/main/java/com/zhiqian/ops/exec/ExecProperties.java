package com.zhiqian.ops.exec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 绑定 application.yml 中的 ops.exec.* 配置。
 */
@Component
@ConfigurationProperties(prefix = "ops.exec")
public class ExecProperties {
    /** 以哪个受限账号执行（最小权限，避免 root）。 */
    private String runAsUser = "opsagent";
    /** 是否通过 sudo -u 降权执行。 */
    private boolean useSudo = false;
    /** 单条命令执行超时（秒）。 */
    private int timeoutSeconds = 20;
    /** 工作目录。 */
    private String workingDir = "/tmp";
    /** 干跑模式：对变更类命令不真正执行，保障演示安全。 */
    private boolean dryRun = true;
    /** 单次请求最大执行轮次（防幻觉计划批量下发/失控、防死循环，保障关键任务确定性）。 */
    private int maxStepsPerRequest = 20;
    /** stdout/stderr 返回给前端的最大内存预览字节数，完整输出另行落盘。 */
    private int outputPreviewBytes = 2 * 1024 * 1024;
    /** 完整命令输出的审计落盘目录。 */
    private String outputAuditDir = "logs/exec-output";
    /** 变更类命令真实执行前的目标文件自动备份目录。 */
    private String backupDir = "logs/backups";
    /** 断点续跑状态目录（回滚账本/待确认计划 JSONL 落盘）；空串表示禁用持久化（纯内存）。 */
    private String stateDir = "";

    public String getRunAsUser() { return runAsUser; }
    public void setRunAsUser(String runAsUser) { this.runAsUser = runAsUser; }
    public boolean isUseSudo() { return useSudo; }
    public void setUseSudo(boolean useSudo) { this.useSudo = useSudo; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public int getMaxStepsPerRequest() { return maxStepsPerRequest; }
    public void setMaxStepsPerRequest(int maxStepsPerRequest) { this.maxStepsPerRequest = maxStepsPerRequest; }
    public int getOutputPreviewBytes() { return outputPreviewBytes; }
    public void setOutputPreviewBytes(int outputPreviewBytes) { this.outputPreviewBytes = outputPreviewBytes; }
    public String getOutputAuditDir() { return outputAuditDir; }
    public void setOutputAuditDir(String outputAuditDir) { this.outputAuditDir = outputAuditDir; }
    public String getBackupDir() { return backupDir; }
    public void setBackupDir(String backupDir) { this.backupDir = backupDir; }
    public String getStateDir() { return stateDir; }
    public void setStateDir(String stateDir) { this.stateDir = stateDir; }
}
