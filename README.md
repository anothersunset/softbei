# 智御 · OS 智能运维 Agent（OpsGuard Agent）

[![CI](https://github.com/anothersunset/softbei/actions/workflows/ci.yml/badge.svg)](https://github.com/anothersunset/softbei/actions/workflows/ci.yml)

> 软件杯赛题作品 —— 一套部署于操作系统的**智能运维 Agent**。它作为自然语言与 OS 之间的桥梁，通过 **MCP（Model Context Protocol）** 赋予大模型「感知系统状态 → 推理决策 → 安全校验 → 受限执行 → 链路溯源」的闭环能力，核心解决 **AI 推理不可控** 带来的「误删库、误操作、危险注入」风险。

## ✨ 一句话定位

> **OpsGuard = 面向 OS 运维的「可审计安全闸门」**：在「大模型 → 操作系统」之间插入一道**意图风险分级 + 反事实回放 + 动作账本回滚**的强制闸门，让 AI 运维从“能跑”升级为“敢在生产跑、且每一步可追责、可回退”。

### 与同类方案的差异

| 维度 | 通用 LLM 安全护栏（内容审核/对齐） | 传统运维告警（Zabbix/Prometheus 规则） | **OpsGuard（本作品）** |
|---|---|---|---|
| 防护对象 | 文本内容合规 | 指标阈值越界 | **运维动作的执行风险** |
| 注入防御 | 模型层对齐，难复现 | 无 | 入口规则层拦截，**语料可复跑、拦截率确定** |
| 危险操作 | 不感知 shell 语义 | 仅告警不阻断 | **SAFE/REVIEW/BLOCK 三级闸门 + 最小权限执行** |
| 出错恢复 | 无 | 人工介入 | **动作账本 + 一键回滚（dry-run）** |
| 可解释 | 黑盒 | 阈值规则 | **反事实回放 + TraceID 全链路审计** |
| 根因定位 | 无 | 单源指标 | **指标↔日志跨源关联 + L1–L3 分级** |

## 1. 这是什么

传统运维依赖复杂脚本与告警，门槛极高。本项目让管理员用**自然语言**运维 Linux 生产节点，例如说一句「帮我清理系统垃圾」，Agent 会：

1. **环境感知**：自动调用 `df / du / ps / ss / journalctl / lsof` 等底层工具，发现是大日志文件占满磁盘；
2. **推理决策**：由大模型生成处置方案与候选指令；
3. **安全护栏校验**：识别候选指令中的高危参数（如 `rm -rf`、关键路径、`chmod 777`），判断该文件是否为**关键数据库日志**、当前权限是否合规；
4. **最小权限执行**：在受限账户下执行，非必要不用 root；
5. **链路溯源**：完整记录闭环日志，支持异常回溺。

## 2. 安全护栏架构（核心）

后端为**八阶段**编排管线，全程 TraceID 落盘可追溯：

```
自然语言指令
   │
   ▼
[① 抗注入检测 PromptInjectionDetector]   ── 命中 → INJECTION_BLOCKED
   │
   ▼
[② OS 环境深度感知 SenseTools(MCP)]      ── 只读采集进程/磁盘/网络/日志(带时间戳事件)
   │
   ▼
[③ 知识/规则检索 ContextRetriever]       ── 为放行决策挂依据(证据)，降低纯 LLM 依赖
   │
   ▼
[④ 大模型推理 LlmClient]                 ── 产出处置方案 + 候选指令(JSON)
   │
   ▼
[⑤ 意图风险校验 IntentRiskGuard]         ── 逐条二次过滤：SAFE / REVIEW / BLOCK；高危需人工确认
   │
   ▼
[⑥ 最小权限执行 LeastPrivilegeExecutor]  ── 受限账户 + 白名单 + 禁用 shell 元字符 + 超时
   │                                        + 熔断器 + 执行轮次上限 + 动作账本/一键回滚
   ▼
[⑦ 跨源根因分析 CrossSourceRca]          ── 指标↔日志时间窗口关联 + L1–L3 分级处置
   │
   ▼
[全程：链路溯源 OpsAuditService] ── RECEIVE→INJECTION_GUARD→SENSE→RETRIEVE→REASON→GUARD→EXECUTE→ANALYZE 闭环 JSONL
```

## 3. ✨ 新增亮点能力（差异化）

> 在初赛五大支柱之上，围绕官方评分维度新增的差异化能力，默认 **只读 / dry-run**，不破坏评测确定性。

### 🛡️ 安全：可量化 + 可解释
- **安全护栏综合评分 SecurityScore**：按「静态风险 30% / 动态意图审计 35% / 受限执行 35%」三维折算为 0–100 安全分，控制台三维雷达可视化，并写入处置报告。
- **反事实回放 Counterfactual Replay**：对被 BLOCK / REVIEW 的命令预估「若放行会发生什么」（受影响文件、不可逆性评级），把抽象护栏变成看得见的拦截价值。
- **红蓝对抗注入看板**：内置红队语料（角色扮演 / 忽略安全策略 / NOPASSWD / 反弹 shell / base64 绕过…），一键真实过护栏，实时统计拦截率 / 误杀率。
- **盲测泛化评测（新）**：`blindset-corpus.yaml` 为未参与调参的对抗变体（编码/同形字/多语言/新措辞），`BlindSetCorpusTest` 输出混淆矩阵与 Precision/Recall/F1，证明「换没见过的样本也能拦」。详见 `docs/redteam-generalization.md`。

### 🔍 根因分析：从「并列罗列」到「跨源关联」
- **跨源 RCA（指标 ↔ 日志）**：巡检采集带时间戳与分类（OOM / DISK_FULL / IO / 依赖雪崩 / 网络分区 / 配置漂移，共 6 类）的结构化日志事件，在 N 分钟时间窗口内做「指标异常 ↔ 日志事件」同根因关联，输出 L1–L3 分级处置与证据链。故障类型×分级×处置矩阵见 `docs/19-RCA故障分级矩阵.md`。
- **LLM 根因总结（可选）**：真实模型下生成自然语言根因叙述；mock 路径规则回退，保证评测可复现。
- **主动巡检 health_inspect**：只读体检 + 健康评分 + 风险预警（HEALTHY / WARNING / DEGRADED / CRITICAL），从被动响应走向主动预测。

### ⚙️ 执行确定性：兜底 + 可恢复
- **动作账本 + 一键回滚**：每个变更自动生成补偿（逆操作）脚本与 append-only 审计账本，回滚默认 dry-run。
- **执行轮次熔断**：单次请求最大执行轮次上限（默认 20，`OPS_EXEC_MAX_STEPS` 可配），防幻觉批量下发；变更类真实执行接入熔断器，连续失败短路高危执行。

### 🔌 协议与体验
- **MCP 双传输通道**：HTTP 与 stdio 复用同一 `McpDispatcher` 路由；stdio 以换行分隔 JSON-RPC，体现「MCP 运维插件化」。
- **MCP 协议合规**：严格对齐 JSON-RPC 2.0 + MCP 规范（协议协商 / 通知 / ping / isError / annotations），MCP-01~08 用例 15/15 全 PASS。
- **SSE 实时思维链**：`/api/ops/chat/stream` 逐阶段推送（类 ChatGPT tool_call），前端「实时思维链」面板逐节点展示并附安全评分 / 回滚建议。
- **可观测性**：接入 `micrometer-registry-prometheus`，打通 `/actuator/prometheus` 指标端点；`deploy/grafana/opsguard-dashboard.json` 提供开箱即用看板。

### ✅ 验收证据

| 项目 | 结果 |
|---|---|
| 安全护栏单元测试 + Mock 覆盖 | **190/190 PASS**（含 guard/pipeline/exec/inspect/controller + 红蓝 @Nested 分组 + 真实故障增补集 RealWorldCorpusTest） |
| 【国产化】龙芯麒麟 LoongArch + 麒麟 V11 真机单测实跳 | **189/189 PASS**（Failures 0 / Errors 0 / Skipped 0，Loongson JDK17；与云端 190 差 1 个平台门控用例，详见 `docs/17` §9） |
| 【真实国产 LLM】龙芯麒麟真机 + DeepSeek 端到端全链路实测 | **红线 BLOCK / 人工确认 / 一键回滚 / 注入拦截 / SSE 实时思维链 / 安全评分 84「B」 全通**（`/api/ops/runtime` 返回 `llmMode=REAL`，详见 `docs/17` §10） |
| 红蓝对抗注入语料回放（48 条） | **48/48 PASS**：注入识别 17/17、注入误拦 0/6、危险命令拦截 10/10、正常命令误拦 0/8 |
| 真实故障场景增补集回放（12 条，核心 48/48 口径不变） | **12/12 PASS**（`RealWorldCorpusTest`） |
| 盲测泛化评测（OOD，未参与调参） | 混淆矩阵 + P/R/F1 由 `BlindsetRunnerTest` 实跳产生（见 `docs/redteam-generalization.md`） |
| 测试覆盖率（JaCoCo 实测） | 指令 75.6% · 分支 60.2% · 行 75.5% |
| 云服务器部署验收（腾讯云 Ubuntu，含真实 LLM provider=xiaomi） | **19/19 PASS** |
| 龙芯麒麟 LoongArch + 麒麟 V11 真机部署 + L3 取证 | **build→run→L3 全通**（`overallLevel=L3`、置信度 90，证据 `backend/rca-evidence/l3-cascade-kylin.json`） |
| MCP 协议合规验证（MCP-01~08） | **15/15 PASS** |

> 上述为本地/云端 `mvn test` 实测结果（环境 JDK 17），完整记录见 `docs/17-本地测评报告.md`；方法学见 `docs/15`，差异化与答辩见 `docs/16`，已知限制与演进路线见 `docs/18-已知限制与演进路线.md`。

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
| 本地测评报告（单测 / 覆盖率 / 红蓝 / RCA 实跳记录） | `docs/17-本地测评报告.md` |
| **【真实国产 LLM 端到端实测】龙芯麒麟真机 + DeepSeek（红线 BLOCK / 人工确认+一键回滚 / 注入拦截 / SSE 实时思维链 / RAG 接地 / securityScore 84，2026-06-29）** | `docs/17-本地测评报告.md` §10（回滚 TraceID `2770bd7b-1b58-47a4-acc1-8b0908ab48af`、SSE TraceID `b3115d79-1ed9-4ff5-a597-8bb0bdc1d11f`） |
| 红蓝对抗注入语料（48 条，可复跑） | `backend/src/test/resources/redteam/injection-corpus.yaml` |
| 盲测泛化语料（OOD 对抗变体） | `backend/src/test/resources/redteam/blindset-corpus.yaml` |
| 红蓝对抗语料回放测试 | `backend/src/test/java/com/zhiqian/ops/eval/RedTeamCorpusTest.java` |
| 真实故障场景增补集（12 条，独立回放，核心 48/48 不变） | `backend/src/test/resources/redteam/realworld-corpus.yaml` |
| 真实故障增补集回放测试（12/12） | `backend/src/test/java/com/zhiqian/ops/eval/RealWorldCorpusTest.java` |
| 盲测泛化混淆矩阵评测 | `backend/src/test/java/com/zhiqian/ops/eval/BlindsetRunnerTest.java` |
| 安全护栏确定性回放测试（33 例） | `backend/src/test/java/com/zhiqian/ops/eval/ScenarioEvaluationTest.java` |
| 跨源 RCA 六类故障注入证据（OOM / DISK_FULL / IO / 依赖雪崩 / 网络分区 / 配置漂移） | `rca-evidence/*.json` |
| 跨源 RCA **L3 级联故障实跳证据**（磁盘 96% CRITICAL ↔ 同源 DISK_FULL/IO 日志，置信度 97%，2026-06-23 云端实跳） | `rca-evidence/l3-cascade.json`（一键复跑 `backend/scripts/collect-l3-evidence.sh`） |
| 跨源 RCA **L3 级联故障·龙芯麒麟真机实跳证据**（LoongArch + 麒麟 V11，磁盘 91% CRITICAL ↔ 同源 DISK_FULL/IO 日志，置信度 90%，2026-06-29 国产化目标环境实跳） | `backend/rca-evidence/l3-cascade-kylin.json`（一键复跑 `backend/scripts/collect-l3-evidence-kylin.sh`，部署/取证见 `docs/22-龙芯麒麟V11部署与L3取证runbook.md`） |
| MCP 协议合规验证报告（MCP-01~08） | `docs/MCP协议合规验证报告.md` |
| 云服务器部署验收测试报告（19/19） | `docs/云服务器部署验收测试报告.md` |
| 龙芯麒麟 V11 部署与 L3 取证 runbook | `docs/22-龙芯麒麟V11部署与L3取证runbook.md` |
| Grafana 可观测看板（6 面板） | `deploy/grafana/opsguard-dashboard.json` |
| auditd 关键路径取证参考规则 | `deploy/auditd-rules.conf` |
| CI 工程化（语料回放 + 覆盖率门禁） | `.github/workflows/ci.yml` |
| 一键验收脚本 | `verify.sh` |

## 4. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 架构 | B/S | 浏览器访问控制台，后端提供 REST + MCP |
| 后端 | Java 17 + Spring Boot 3.3 | 国产化兼容好，可在 LoongArch 上用歕昂/Loongson JDK 运行 |
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
│       │   ├── exec/        # 最小权限执行器 + 熔断 + 动作账本/回滚
│       │   ├── trace/       # 推理链路溯源审计
│       │   ├── analyzer/    # 跨源根因分析 + 主动巡检
│       │   ├── mcp/         # MCP JSON-RPC 端点（HTTP / stdio 双通道）
│       │   ├── pipeline/    # 八阶段编排管线
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

启用后，REST `/api/**` 与 HTTP MCP `/mcp/**` 均需携带 `X-Ops-Token: <令牌>` 或 `Authorization: Bearer <令牌>`。如果绑定到非回环地址但未配置令牌，服务会拒绝启动。

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
