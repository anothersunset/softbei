# 智御 · OS 智能运维 Agent（OpsGuard Agent）

[![CI](https://github.com/anothersunset/softbei/actions/workflows/ci.yml/badge.svg)](https://github.com/anothersunset/softbei/actions/workflows/ci.yml)

> 软件杯赛题作品 —— 一套部署于操作系统的**智能运维 Agent**。它作为自然语言与 OS 之间的桥梁，通过 **MCP（Model Context Protocol）** 赋予大模型「感知系统状态 → 推理决策 → 安全校验 → 受限执行 → 链路溯源」的闭环能力，核心解决 **AI 推理不可控** 带来的「误删库、误操作、危险注入」风险。

## ✨ 一句话定位

> **OpsGuard = 面向 OS 运维的「可审计安全闸门」**：在「大模型 → 操作系统」之间插入一道**四级意图风险分级 + 反事实回放 + 变更回滚建议**的强制闸门，让 AI 运维从“能跑”升级为“敢在生产跑、且每一步可追责、有恢复指引”。

### 与同类方案的差异

| 维度 | 通用 LLM 安全护栏（内容审核/对齐） | 传统运维告警（Zabbix/Prometheus 规则） | **OpsGuard（本作品）** |
|---|---|---|---|
| 防护对象 | 文本内容合规 | 指标阈值越界 | **运维动作的执行风险** |
| 注入防御 | 模型层对齐，难复现 | 无 | 入口规则层拦截，**语料可复跑、拦截率确定** |
| 危险操作 | 不感知 shell 语义 | 仅告警不阻断 | **READONLY / EXECUTABLE / IRREVERSIBLE / BLOCK 四级闸门 + 最小权限执行** |
| 出错恢复 | 无 | 人工介入 | **内存动作账本 + 回滚建议/补偿指引（默认 dry-run）** |
| 可解释 | 黑盒 | 阈值规则 | **反事实回放 + TraceID 全链路审计** |
| 根因定位 | 无 | 单源指标 | **指标↔日志跨源关联 + L1–L3 分级** |

## 1. 这是什么

传统运维依赖复杂脚本与告警，门槛极高。本项目让管理员用**自然语言**运维 Linux 生产节点，例如说一句「帮我清理系统垃圾」，Agent 会：

1. **环境感知**：自动调用 `df / du / ps / ss / journalctl / lsof` 等底层工具，发现是大日志文件占满磁盘；
2. **推理决策**：由大模型生成处置方案与候选指令；
3. **安全护栏校验**：识别候选指令中的高危参数（如 `rm -rf`、关键路径、`chmod 777`），判断该文件是否为**关键数据库日志**、当前权限是否合规；
4. **最小权限执行**：在受限账户下执行，非必要不用 root；
5. **链路溯源**：完整记录闭环日志，支持异常回溯。

## 2. 安全护栏架构（核心）

后端为**九阶段**编排管线，全程 TraceID 落盘可追溯：

```
自然语言指令
   │
   ▼
[① 抗注入检测 PromptInjectionDetector]   ── 命中 → INJECTION_BLOCKED
   │
   ▼
[② OS 环境深度感知 SenseTools(MCP)]      ── 真实模型：ReAct 循环按需感知(Thought→Action→Observation)
   │                                        经 MCP 内部总线参数化调用；Mock：全量只读采集(评测确定性)
   │
   ▼
[③ 知识/规则检索 ContextRetriever]       ── 为放行决策挂依据(证据)，降低纯 LLM 依赖
   │
   ▼
[④ 大模型推理 LlmClient]                 ── 产出处置方案 + 候选指令(JSON)
   │
   ▼
[⑤ 任务级规划 ExecutionPlanner]          ── Plan-and-Execute：证据采集 / 受控处置 / 复核闭环
   │
   ▼
[⑥ 意图风险校验 IntentRiskGuard]         ── 逐条二次过滤：READONLY / EXECUTABLE / IRREVERSIBLE / BLOCK
   │
   ▼
[⑦ 最小权限执行 LeastPrivilegeExecutor]  ── 受限账户 + 白名单 + 管道分段裁决 + 超时
   │                                        + 熔断器 + 轮次上限 + 执行前自动备份 + VERIFY 复核闭环
   │                                        + 动作账本(JSONL 落盘·重启不丢)/一键回滚
   ▼
[⑧ 跨源根因分析 CrossSourceRca]          ── 指标↔日志时间窗口关联 + L1–L3 分级处置
   │
   ▼
[全程：链路溯源 OpsAuditService] ── RECEIVE→INJECTION_GUARD→SENSE→RETRIEVE→REASON→PLAN→GUARD→EXECUTE→ANALYZE 闭环 JSONL
```

## 3. ✨ 新增亮点能力（差异化）

> 在初赛五大支柱之上，围绕官方评分维度新增的差异化能力，默认 **只读 / dry-run**，不破坏评测确定性。

### 🤖 Agent 架构：ReAct × Plan-Execute 混合（对齐官方赛题解读推荐架构）
- **ReAct 按需感知循环**：真实模型下，LLM 经 **MCP 内部总线**（`McpDispatcher`，自己吃自己的狗粮）自主选择感知工具并**参数化调用**（如 `log_sense(unit, lines)`、`disk_sense(path)`），Thought→Action→Observation 逐轮迭代（上限 `OPS_REACT_MAX_ROUNDS` 可配），证据足够即停——从「无差别全量采集」升级为「按需精确感知」；Mock/评测路径保持全量感知，确定性口径不变。
- **意图一致性交叉校验（动态意图审计）**：`IntentConsistencyChecker` 用独立审计 prompt 二次校验「候选命令是否服务于用户原始诉求」，检出幻觉偏差/夹带命令即**升级**为需人工确认；结果只升不降，规则层确定性底线不破。
- **主备模型自动切换**：`FailoverLlmClient` 主→备→Mock 降级链，失败自动切换、冷却期后回切主模型探活；降级到 Mock 时 ReAct/语义校验自动关闭，服务永不因模型不可用而中断。

### 🛡️ 安全：可量化 + 可解释
- **显性 Plan-and-Execute**：`ExecutionPlanner` 将模型候选命令拆成 OBSERVE / CHANGE / VERIFY 任务，标注依赖、证据、风险预期与执行状态；复杂场景不再只是“几条命令列表”。
- **管道感知裁决**：`ps aux | grep java` 这类全只读管道命令按段裁决放行（READONLY），任一段变更取最高风险；`| sh`/`$()`/反引号等解释器逃逸仍一刀切 BLOCK——真实 LLM 生成的管道命令不再误杀。
- **安全护栏综合评分 SecurityScore**：按「静态风险 30% / 动态意图审计 35% / 受限执行 35%」三维折算为 0–100 安全分，控制台三维雷达可视化，并写入处置报告。
- **反事实回放 Counterfactual Replay**：对被 BLOCK / EXECUTABLE / IRREVERSIBLE 的命令预估「若放行会发生什么」（受影响文件、不可逆性评级），把抽象护栏变成看得见的拦截价值。
- **敏感输出脱敏**：执行预览、完整落盘审计、回滚与对外响应统一遮蔽 password/token/secret/private key/JDBC 凭据等敏感片段，降低日志外泄风险。
- **红蓝对抗注入看板**：内置红队语料（角色扮演 / 忽略安全策略 / NOPASSWD / 反弹 shell / base64 绕过…），一键真实过护栏，实时统计拦截率 / 误杀率。
- **盲测泛化评测（新）**：`blindset-corpus.yaml` 为未参与调参的对抗变体（编码/同形字/多语言/新措辞），`BlindSetCorpusTest` 输出混淆矩阵与 Precision/Recall/F1，证明「换没见过的样本也能拦」。详见 `docs/redteam-generalization.md`。

### 🔍 根因分析：从「并列罗列」到「跨源关联」
- **跨源 RCA（指标 ↔ 日志）**：巡检采集带时间戳与分类（OOM / DISK_FULL / IO / 依赖雪崩 / 网络分区 / 配置漂移，共 6 类）的结构化日志事件，在 N 分钟时间窗口内做「指标异常 ↔ 日志事件」同根因关联，输出 L1–L3 分级处置与证据链。故障类型×分级×处置矩阵见 `docs/19-RCA故障分级矩阵.md`。
- **LLM 根因总结（可选）**：真实模型下生成自然语言根因叙述；mock 路径规则回退，保证评测可复现。
- **主动巡检 health_inspect**：只读体检 + 健康评分 + 风险预警（HEALTHY / WARNING / DEGRADED / CRITICAL），从被动响应走向主动预测。
- **预测性感知（感知成熟度 3 级）**：巡检采样历史做趋势外推，输出「按当前增速预计 X 小时后磁盘写满/内存耗尽」的预测报告（`TrendPrediction`），24h 内耗尽升 WARN 进摘要——从「发现已发生的故障」走向「预测将发生的故障」。

### ⚙️ 执行确定性：兜底 + 可恢复
- **执行前自动备份 + 一键回滚**：变更命令真实执行前自动备份目标文件（`PreChangeBackup`，按 traceId 归档），回滚账本生成「从备份恢复」补偿指令；`/api/ops/rollback/{traceId}` 一键回放，恢复命令本身同样过护栏闸门。
- **VERIFY 复核闭环**：变更真实落地后自动运行派生的只读复核探针（systemctl→is-active、rm→stat 等固定模板），全过 → 任务 `VERIFIED`，失败 → `VERIFY_FAILED` 并挂回滚指引——Plan-and-Execute 从展示层变成执行语义。
- **断点续跑（状态落盘）**：回滚账本与待确认计划 JSONL 落盘（`OPS_STATE_DIR`），服务重启后原 traceId 仍可确认执行「已被审阅的同一计划」、仍可一键回滚——重启不丢账。
- **执行轮次熔断**：单次请求最大执行轮次上限（默认 20，`OPS_EXEC_MAX_STEPS` 可配），防幻觉批量下发；变更类真实执行接入熔断器，连续失败短路高危执行。

### 🔌 协议与体验
- **MCP 双传输通道**：HTTP 与 stdio 复用同一 `McpDispatcher` 路由；stdio 以换行分隔 JSON-RPC，体现「MCP 运维插件化」。
- **MCP 协议合规**：严格对齐 JSON-RPC 2.0 + MCP 规范（协议协商 / 通知 / ping / isError / annotations），MCP-01~08 用例 15/15 全 PASS。
- **MCP 工具面：感知 + 变更双平面（新）**：11 个 MCP 插件——8 个只读感知/巡检（system/process/disk/network/log/**db/metrics**\_sense + health_inspect，`db_sense` 免凭据探测 MySQL/PostgreSQL/Redis 进程·端口·连接数，`metrics_sense` 抓取 node_exporter / Actuator 的 Prometheus 指标，对齐赛题「数据库、监控系统」集成）+ 3 个**变更类工具**（`log_rotate` 日志轮转 / `service_restart` 服务重启 / `config_backup` 配置备份），变更工具内置 `GuardedMutationExecutor` 安全闭环：护栏裁决（红线 confirm 也无法越过）→ `confirm=true` 二次确认 → 执行前自动备份 → 回滚账本 → 溯源审计，注解按工具自声明（`readOnlyHint`/`destructiveHint`）。
- **溯源可持续运行（新）**：溯源 JSONL 超阈值自动轮转归档（`OPS_TRACE_ROTATE_BYTES`，默认 32MB），文件不无限增长；查询内存未命中时自动回扫当前+归档文件重建——重启后、被 LRU 淘汰后、已归档的历史 traceId 依然可查。
- **SSE 实时思维链**：`/api/ops/chat/stream` 逐阶段推送（类 ChatGPT tool_call），前端「实时思维链」面板逐节点展示并附安全评分 / 回滚建议。
- **可观测性**：接入 `micrometer-registry-prometheus`，打通 `/actuator/prometheus` 指标端点；`deploy/grafana/opsguard-dashboard.json` 提供开箱即用看板。

### ✅ 验收证据

| 项目 | 结果 |
|---|---|
| 安全护栏单元测试 + Mock 覆盖 | **248/248 PASS**（2026-07-05 本地 `mvn test`；含 guard/pipeline/planner/exec/inspect/controller + 红蓝语料 + ReAct 感知 + 意图交叉校验 + 备份/回滚/断点续跑 + 主备切换 + 预测性感知） |
| 红蓝对抗注入语料回放（48 条） | **48/48 PASS**：注入识别 17/17、注入误拦 0/6、危险命令拦截 10/10、正常命令误拦 0/8 |
| 真实故障场景增补集回放（12 条，核心 48/48 口径不变） | **12/12 PASS**（`RealWorldCorpusTest`） |
| 盲测泛化评测（OOD，未参与调参） | 混淆矩阵 + P/R/F1 由 `BlindsetRunnerTest` 实跑产生（见 `docs/redteam-generalization.md`） |
| 测试覆盖率（JaCoCo 实测） | 指令 84.4% · 分支 67.9% · 行 85.0%（2026-07-02 本地 JaCoCo CSV） |
| 历史云服务器部署验收（腾讯云 Ubuntu，含真实 LLM provider=xiaomi） | **19/19 PASS**（见历史报告；四级风险分级改造后尚未重新跑云端全量验收） |
| MCP 协议合规验证（MCP-01~08） | **15/15 PASS** |

> 当前代码最近一次本地验证为 2026-07-05 `mvn test` 248/248 PASS（JDK 21，项目 release 17）。云服务器验收为历史归档记录，完整记录见 `docs/17-本地测评报告.md` 与 `docs/云服务器部署验收测试报告.md`；方法学见 `docs/15`，差异化与答辩见 `docs/16`，已知限制与演进路线见 `docs/18-已知限制与演进路线.md`。

### 📦 交付材料（`docs/_evidence/`）

| 材料 | 文件 | 说明 |
|------|------|------|
| 演示 PPT (.pptx) | `docs/_evidence/OpsGuard-演示PPT.pptx` | 13 页，pptxgenjs 生成，深海军蓝主题 |
| 演示 PPT (HTML) | `docs/_evidence/OpsGuard-瑞士风PPT.html` | 10 页瑞士风，浏览器直接打开 |
| Grafana 实景看板 | `docs/_evidence/grafana-screenshot.png` | Prometheus 实数据，6 面板 |
| JaCoCo 覆盖率 | `docs/_evidence/jacoco-overview.png` | 14 个包覆盖率明细 |
| 红蓝对抗指标 | `docs/_evidence/redteam-metrics.png` | 4 指标全绿 |
| 演示录屏 | `docs/_evidence/demo.cast` | asciinema 终端录屏 |
| 在线演示 | https://asciinema.org/a/l3xgq0gG6mENBG85dfCKsMrKq | 浏览器可播放 |

### 📁 验收证据索引

| 证据 | 路径 |
|---|---|
| 本地测评报告（单测 / 覆盖率 / 红蓝 / RCA 实跑记录） | `docs/17-本地测评报告.md` |
| 红蓝对抗注入语料（48 条，可复跑） | `backend/src/test/resources/redteam/injection-corpus.yaml` |
| 盲测泛化语料（OOD 对抗变体） | `backend/src/test/resources/redteam/blindset-corpus.yaml` |
| 红蓝对抗语料回放测试 | `backend/src/test/java/com/zhiqian/ops/eval/RedTeamCorpusTest.java` |
| 真实故障场景增补集（12 条，独立回放，核心 48/48 不变） | `backend/src/test/resources/redteam/realworld-corpus.yaml` |
| 真实故障增补集回放测试（12/12） | `backend/src/test/java/com/zhiqian/ops/eval/RealWorldCorpusTest.java` |
| 盲测泛化混淆矩阵评测 | `backend/src/test/java/com/zhiqian/ops/eval/BlindsetRunnerTest.java` |
| 安全护栏确定性回放测试（33 例） | `backend/src/test/java/com/zhiqian/ops/eval/ScenarioEvaluationTest.java` |
| 跨源 RCA 六类故障注入证据（OOM / DISK_FULL / IO / 依赖雪崩 / 网络分区 / 配置漂移） | `rca-evidence/*.json` |
| MCP 协议合规验证报告（MCP-01~08） | `docs/MCP协议合规验证报告.md` |
| 历史云服务器部署验收测试报告（19/19） | `docs/云服务器部署验收测试报告.md` |
| Grafana 可观测看板（6 面板） | `deploy/grafana/opsguard-dashboard.json` |
| auditd 关键路径取证参考规则 | `deploy/auditd-rules.conf` |
| CI 工程化（语料回放 + 覆盖率门禁） | `.github/workflows/ci.yml` |
| 一键验收脚本 | `verify.sh` |

## 4. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 架构 | B/S | 浏览器访问控制台，后端提供 REST + MCP |
| 后端 | Java 17 + Spring Boot 3.3 | 国产化兼容好，可在 LoongArch 上用歕昇/Loongson JDK 运行 |
| 大模型 | DeepSeek / Qwen3（国产开源） | 通过 `LlmClient` 抽象，内置 `MockLlmClient` 可离线演示 |
| 协议 | MCP (JSON-RPC 2.0) | `tools/list`、`tools/call` 暴露运维插件 |
| 前端 | 原生 HTML + JS（零构建） | 架构无关，LoongArch 直接可跑 |
| 部署 | Docker（loong64） + 麒麟 V11 | `deploy/Dockerfile.loong64` |

## 5. 目录结构

```
softbei/
├── backend/                 # Spring Boot 后端（核心）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/zhiqian/ops/
│       │   ├── agent/       # Agent 核心 + MCP 感知工具插件
│       │   ├── llm/         # 大模型客户端（DeepSeek / Mock）
│       │   ├── guard/       # 安全护栏：意图风险校验 + 抗注入 + 安全评分
│       │   ├── exec/        # 最小权限执行器 + 熔断 + 动作账本/回滚建议
│       │   ├── trace/       # 推理链路溯源审计
│       │   ├── analyzer/    # 跨源根因分析 + 主动巡检
│       │   ├── mcp/         # MCP JSON-RPC 端点（HTTP / stdio 双通道）
│       │   ├── planner/     # 显性 Plan-and-Execute 任务级规划
│       │   ├── pipeline/    # 九阶段编排管线
│       │   └── web/         # REST 控制器 + SSE 实时思维链
│       └── resources/
│           ├── application.yml
│           ├── risk-rules.yaml     # 安全规则库（配置化，重启生效）
│           └── static/index.html   # B/S 控制台
├── deploy/                  # LoongArch + 麒麟 V11 部署
├── docs/                    # 初赛提交文档 + 增强阶段设计/验收报告
├── rca-evidence/            # 跨源 RCA 故障注入证据（6 类：OOM / DISK_FULL / IO / 依赖雪崩 / 网络分区 / 配置漂移）
├── verify.sh                # 一键验收脚本
└── README.md
```

## 6. 快速开始

```bash
cd backend
mvn spring-boot:run          # 默认 MockLlmClient，离线即可演示
# 浏览器打开 http://127.0.0.1:8080
```

### 部署信任边界

默认配置仅绑定 `127.0.0.1`，适合本机答辩演示；如果要在内网或公网开放访问，必须显式设置绑定地址并启用入口令牌：

```bash
export OPS_BIND_ADDRESS=0.0.0.0
export OPS_API_TOKEN=<强随机令牌>
```

启用后，REST `/api/**`、HTTP MCP `/mcp/**` 与 `/actuator/**` 均需携带 `X-Ops-Token: <令牌>` 或 `Authorization: Bearer <令牌>`。如果绑定到非回环地址但未配置令牌，服务会拒绝启动。

接入真实大模型（DeepSeek）：

```bash
export OPS_LLM_PROVIDER=deepseek
export OPS_LLM_API_KEY=sk-xxxx
mvn spring-boot:run
```

变异测试（证明用例有效性，非仅刷覆盖率）：

```bash
cd backend
mvn -P mutation org.pitest:pitest-maven:mutationCoverage
# 报告：target/pit-reports/index.html
```

详见 `docs/06-安装部署文档.md`。

## 7. 提交物清单（初赛）

| # | 文档 | 位置 |
|---|---|---|
| 1 | 软件功能需求分析文档 | `docs/01-需求分析.md` |
| 2 | 软件功能设计文档 | `docs/02-功能设计.md` |
| 3 | 软件产品说明书 | `docs/03-产品说明书.md` |
| 4 | 软件功能测试报告 | `docs/04-功能测试报告.md` |
| 5 | 软件性能测试报告 | `docs/05-性能测试报告.md` |
| 6 | 软件安装包及部署文档 | `docs/06-安装部署文档.md` |
| 7 | 源代码 | 本仓库 `backend/` |
| 8 | 演示 PPT 大纲 | `docs/08-演示PPT大纲.md` |
| 9 | 演示视频脚本（≤7min） | `docs/09-演示视频脚本.md` |

> 增强阶段新增文档见 `docs/`（红蓝对抗注入看板设计、执行兜底与回滚、MCP 协议合规验证报告、云服务器部署验收测试报告、红蓝对抗语料与CI工程化、决赛差异化与答辩Q&A、本地测评报告、泛化评测方法学、已知限制与演进路线 等）。

## 8. 著作权

参赛团队自主开发部分著作权归参赛团队所有。
