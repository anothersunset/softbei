package com.zhiqian.ops.inspect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主动巡检阈值配置，绑定 application.yml 中的 ops.inspect.*。
 * 规则外置于配置，便于评审与按环境调整（呼应「可通过 YAML 自定义运维规则」）。
 */
@Component
@ConfigurationProperties(prefix = "ops.inspect")
public class InspectionProperties {
    /** 是否启用定时主动巡检。 */
    private boolean scheduledEnabled = false;
    /** 定时巡检间隔(毫秒)。 */
    private long intervalMs = 300000;
    /** 磁盘使用率告警阈值(%)。 */
    private int diskWarnPercent = 80;
    /** 磁盘使用率严重阈值(%)。 */
    private int diskCriticalPercent = 90;
    /** 内存使用率告警阈值(%)。 */
    private int memWarnPercent = 85;
    /** 内存使用率严重阈值(%)。 */
    private int memCriticalPercent = 95;
    /** 单核负载告警阈值。 */
    private double loadWarnPerCore = 1.0;
    /** 单核负载严重阈值。 */
    private double loadCriticalPerCore = 2.0;
    /** 僵尸进程告警阈值(个)。 */
    private int zombieWarn = 1;
    /** 僵尸进程严重阈值(个)。 */
    private int zombieCritical = 5;
    /** 系统错误日志条数告警阈值。 */
    private int logErrorWarn = 20;
    /** 系统错误日志条数严重阈值。 */
    private int logErrorCritical = 100;
    /** 跨源关联的时间窗口(分钟)：仅将该窗口内的错误日志与指标异常关联为同一根因。 */
    private int logWindowMinutes = 5;

    public boolean isScheduledEnabled() { return scheduledEnabled; }
    public void setScheduledEnabled(boolean v) { this.scheduledEnabled = v; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long v) { this.intervalMs = v; }
    public int getDiskWarnPercent() { return diskWarnPercent; }
    public void setDiskWarnPercent(int v) { this.diskWarnPercent = v; }
    public int getDiskCriticalPercent() { return diskCriticalPercent; }
    public void setDiskCriticalPercent(int v) { this.diskCriticalPercent = v; }
    public int getMemWarnPercent() { return memWarnPercent; }
    public void setMemWarnPercent(int v) { this.memWarnPercent = v; }
    public int getMemCriticalPercent() { return memCriticalPercent; }
    public void setMemCriticalPercent(int v) { this.memCriticalPercent = v; }
    public double getLoadWarnPerCore() { return loadWarnPerCore; }
    public void setLoadWarnPerCore(double v) { this.loadWarnPerCore = v; }
    public double getLoadCriticalPerCore() { return loadCriticalPerCore; }
    public void setLoadCriticalPerCore(double v) { this.loadCriticalPerCore = v; }
    public int getZombieWarn() { return zombieWarn; }
    public void setZombieWarn(int v) { this.zombieWarn = v; }
    public int getZombieCritical() { return zombieCritical; }
    public void setZombieCritical(int v) { this.zombieCritical = v; }
    public int getLogErrorWarn() { return logErrorWarn; }
    public void setLogErrorWarn(int v) { this.logErrorWarn = v; }
    public int getLogErrorCritical() { return logErrorCritical; }
    public void setLogErrorCritical(int v) { this.logErrorCritical = v; }
    public int getLogWindowMinutes() { return logWindowMinutes; }
    public void setLogWindowMinutes(int v) { this.logWindowMinutes = v; }
}
