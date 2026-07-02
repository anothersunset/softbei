# RCA 故障类型 × 分级 × 处置 矩阵

> 回应「RCA 偏窄（仅 3 类、均 L2）」质疑：明确已落地范围与规划范围，不混淆「已做」与「在做」。

## 分级定义

- **L1**：单点轻微异常，只读建议即可。
- **L2**：明确单根因资源事件（内存/磁盘/IO），变更处置需人工确认后执行。
- **L3**：跨服务/跨资源连锁故障，需根因收敛 + 多步处置 + 回滚预案。

## 矩阵

| 故障类型 | 典型信号（指标↔日志） | 分级 | 处置策略 | 状态 |
|---|---|---|---|---|
| 内存耗尽 OOM | RSS 陡升 ↔ `Out of memory: Killed process` | L2 | 定位进程 + 建议重启/限制，变更走 EXECUTABLE/IRREVERSIBLE 人工确认 | ✅ 已落地（`rca-evidence/oom.json`） |
| 磁盘占满 DISK_FULL | 磁盘使用率>阈值 ↔ `No space left on device` | L2 | 定位大文件 + dry-run 清理建议 | ✅ 已落地（`rca-evidence/disk_full.json`） |
| IO 阻塞 | iowait 高 ↔ `I/O error` / 设备超时 | L2 | 定位设备/进程 + 只读诊断 | ✅ 已落地（`rca-evidence/io.json`） |
| 依赖雪崩 | 上游超时激增 ↔ `503 Service Unavailable` / `circuit breaker` / `connection pool exhausted` | L2-L3 | 根因收敛到上游 + 熔断/限流建议 | ✅ 已落地（`rca-evidence/network-dependency-config.json`） |
| 网络分区 | 丢包/延迟突增 ↔ `Connection refused` / `timed out` / DNS 失败 | L2-L3 | 拓扑定位 + 只读连通性诊断 | ✅ 已落地（同上） |
| 配置漂移 | 变更后错误率陡升 ↔ `Configuration error` / `syntax error` / `parse fail` | L2-L3 | 关联最近变更 + 动作账本/回滚建议 | ✅ 已落地（同上） |

> **L3 触发条件**：指标域 CRITICAL + 日志域同源错误类型时间窗口匹配。当前实跑环境（阿里云 ECS 1.6GB）磁盘 83%(WARN)、内存 70%(OK)、负载 0.25(OK)，无 CRITICAL 指标，故新故障类型以 L2 呈现。在目标麒麟/LoongArch 生产节点上，若指标达 CRITICAL 且日志匹配，将自动升级为 L3。
