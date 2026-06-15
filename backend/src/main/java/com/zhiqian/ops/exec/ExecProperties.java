package com.zhiqian.ops.exec;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行器配置（最小权限原则）。
 */
@ConfigurationProperties(prefix = "ops.exec")
public class ExecProperties {
    /** 受限运维账户，非必要不用 root。 */
    private String runAsUser = "opsagent";
    /** 是否通过 sudo -u 切换到受限账户执行。 */
    private boolean useSudo = false;
    /** 单条命令超时（秒）。 */
    private int timeoutSeconds = 20;
    /** 工作目录。 */
    private String workingDir = "/tmp";
    /** 演示安全模式：true 时高危变更不真正落盘。 */
    private boolean dryRun = true;

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
}
