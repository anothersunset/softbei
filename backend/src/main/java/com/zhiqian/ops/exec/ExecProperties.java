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
