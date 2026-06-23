# RCA 故障类型 × 分级 × 处置 矩阵

> 回应「RCA 偏窄（仅 3 类、均 L2）」质疑：明确已落地范围与规划范围，不混淆「已做」与「在做」。

## 分级定义

- **L1**：单点轻微异常，只读建议即可。
- **L2**：明确单根因资源事件（内存/磁盘/IO），需 REVIEW 后处置。
- **L3**：跨服务/跨资源连锁故障，需根因收敛 + 多步处置 + 回滚预案。

## 矩阵

| 故障类型 | 典型信号（指标↔日志） | 分级 | 处置策略 | 状态 |
|---|---|---|---|---|
| 内存耗尽 OOM | RSS 陡升 ↔ `Out of memory: Killed process` | L2 | 定位进程 + 建议重启/限制，变更走 REVIEW | ✅ 已落地（`rca-evidence/oom.json`） |
| 磁盘占满 DISK_FULL | 磁盘使用率>阈值 ↔ `No space left on device` | L2 | 定位大文件 + dry-run 清理建议 | ✅ 已落地（`rca-evidence/disk_full.json`） |
| IO 阻塞 | iowait 高 ↔ `I/O error` / 设备超时 | L2 | 定位设备/进程 + 只读诊断 | ✅ 已落地（`rca-evidence/io.json`） |
| 依赖雪崩 | 上游超时激增 ↔ `503 Service Unavailable` / `circuit breaker` / `connection pool exhausted` | L2-L3 | 根因收敛到上游 + 熔断/限流建议 | ✅ 已落地（`rca-evidence/network-dependency-config.json`） |
| 网络分区 | 丢包/延迟突增 ↔ `Connection refused` / `timed out` / DNS 失败 | L2-L3 | 拓扑定位 + 只读连通性诊断 | ✅ 已落地（同上） |
| 配置漂移 | 变更后错误率陡升 ↔ `Configuration error` / `syntax error` / `parse fail` | L2-L3 | 关联最近变更 + 动作账本回滚 | ✅ 已落地（同上） |
| **磁盘写满级联（实跑 L3）** | 磁盘 96% CRITICAL ↔ 5min 内 `No space left on device`(DISK_FULL) + `EXT4-fs error`(IO) 同源 | **L3** | 立即升级人工告警 + 处置预案 + 全程一键回滚账本 | ✅ 云端实跑（2026-06-23，置信度 97%，`rca-evidence/l3-cascade.json`） |

> **L3 触发条件**：指标域 CRITICAL + 日志域同源错误类型时间窗口（5min）匹配（源码 `CrossSourceRca.grade`：`metricCritical && (logElevated || kindMatch)`）。
>
> **✅ 已实跑取得 L3（2026-06-23，阿里云 ECS）**：人为压满根分区至 **96%**（`disk-usage` CRITICAL，阈值 90%），并在 5 分钟窗口内注入同源 `No space left on device`(DISK_FULL) 与 `EXT4-fs error`(IO) 错误日志；跨源根因分析判定 **`overallLevel=L3`、置信度 97%**，生成 3 条洞察（L3 磁盘 / L2 内存 / L2 负载），证据链含「时间窗口命中同源错误日志 2 条」。证据文件：`rca-evidence/l3-cascade.json`，TraceID `34f2aaee-b0c0-4a21-925a-a9f16519b92b`。一键复跑脚本：`backend/scripts/collect-l3-evidence.sh`。
>
> 原约束说明仍适用：若在未压满资源的常态环境（如未压测的 ECS），指标未达 CRITICAL 时新故障类型以 L2 呈现；只有指标达 CRITICAL 且同源日志匹配才会升级为 L3。
