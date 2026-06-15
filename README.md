# 智御 · OS 智能运维 Agent（OpsGuard Agent）

> 软件杯赛题作品 —— 一套部署于操作系统的**智能运维 Agent**。它作为自然语言与 OS 之间的桥梁，通过 **MCP（Model Context Protocol）** 赋予大模型「感知系统状态 → 推理决策 → 安全校验 → 受限执行 → 链路溯源」的闭环能力，核心解决 **AI 推理不可控** 带来的「误删库、误操作、危险注入」风险。

## 1. 这是什么

传统运维依赖复杂脚本与告警，门槛极高。本项目让管理员用**自然语言**运维 Linux 生产节点，例如说一句「帮我清理系统垃圾」，Agent 会：

1. **环境感知**：自动调用 `df / du / ps / ss / journalctl / lsof` 等底层工具，发现是大日志文件占满磁盘；
2. **推理决策**：由大模型生成处置方案与候选指令；
3. **安全护栏校验**：识别候选指令中的高危参数（如 `rm -rf`、关键路径、`chmod 777`），判断该文件是否为**关键数据库日志**、当前权限是否合规；
4. **最小权限执行**：在受限账户下执行，非必要不用 root；
5. **链路溯源**：完整记录闭环日志，支持异常回溯。

## 2. 安全护栏架构（核心）

```
自然语言指令
   │
   ▼
[① 抗注入检测 PromptInjectionDetector]  ── 命中 → 拒绝
   │
   ▼
[② OS 环境深度感知 SenseTools(MCP)]     ── 只读采集进程/磁盘/网络/日志上下文
   │
   ▼
[③ 大模型推理 LlmClient]                ── 产出处置方案 + 候选指令(JSON)
   │
   ▼
[④ 意图风险校验 IntentRiskGuard]        ── 对每条原始指令做"二次过滤"：SAFE / REVIEW / BLOCK
   │                                       命中红线 → BLOCK；高危 → 需人工确认
   ▼
[⑤ 最小权限执行 LeastPrivilegeExecutor] ── 受限账户 + 命令白名单 + 禁用 shell 元字符 + 超时
   │
   ▼
[⑥ 根因分析 RootCauseAnalyzer]
   │
   ▼
[全程：推理链路溯源 OpsAuditService]     ── RECEIVE→SENSE→REASON→GUARD→EXECUTE→ANALYZE 闭环 JSONL
```

## 3. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 架构 | B/S | 浏览器访问控制台，后端提供 REST + MCP |
| 后端 | Java 17 + Spring Boot 3.3 | 国产化兼容好，可在 LoongArch 上用毕昇/Loongson JDK 运行 |
| 大模型 | DeepSeek / Qwen3（国产开源） | 通过 `LlmClient` 抽象，内置 `MockLlmClient` 可离线演示 |
| 协议 | MCP (JSON-RPC 2.0) | `tools/list`、`tools/call` 暴露运维插件 |
| 前端 | 原生 HTML + JS（零构建） | 架构无关，LoongArch 直接可跑 |
| 部署 | Docker（loong64） + 麒麟 V11 | `deploy/Dockerfile.loong64` |

## 4. 目录结构

```
softbei/
├── backend/                 # Spring Boot 后端（核心）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/zhiqian/ops/
│       │   ├── agent/       # Agent 核心 + MCP 感知工具插件
│       │   ├── llm/         # 大模型客户端（DeepSeek / Mock）
│       │   ├── guard/       # 安全护栏：意图风险校验 + 抗注入
│       │   ├── exec/        # 最小权限执行器
│       │   ├── trace/       # 推理链路溯源审计
│       │   ├── analyzer/    # 根因分析
│       │   ├── mcp/         # MCP JSON-RPC 端点
│       │   ├── pipeline/    # 编排管线
│       │   └── web/         # REST 控制器
│       └── resources/
│           ├── application.yml
│           ├── risk-rules.yaml     # 安全规则库（可热配置）
│           └── static/index.html   # B/S 控制台
├── deploy/                  # LoongArch + 麒麟 V11 部署
├── docs/                    # 9 份初赛提交文档
└── README.md
```

## 5. 快速开始

```bash
cd backend
mvn spring-boot:run          # 默认 MockLlmClient，离线即可演示
# 浏览器打开 http://localhost:8080
```

接入真实大模型（DeepSeek）：

```bash
export OPS_LLM_PROVIDER=deepseek
export OPS_LLM_API_KEY=sk-xxxx
mvn spring-boot:run
```

详见 `docs/06-软件安装包及部署文档.md`。

## 6. 提交物清单（初赛）

| # | 文档 | 位置 |
|---|---|---|
| 1 | 软件功能需求分析文档 | `docs/01-软件功能需求分析文档.md` |
| 2 | 软件功能设计文档 | `docs/02-软件功能设计文档.md` |
| 3 | 软件产品说明书 | `docs/03-软件产品说明书.md` |
| 4 | 软件功能测试报告 | `docs/04-软件功能测试报告.md` |
| 5 | 软件性能测试报告 | `docs/05-软件性能测试报告.md` |
| 6 | 软件安装包及部署文档 | `docs/06-软件安装包及部署文档.md` |
| 7 | 源代码 | 本仓库 `backend/` |
| 8 | 演示 PPT 大纲 | `docs/08-演示PPT大纲.md` |
| 9 | 演示视频脚本（≤7min） | `docs/09-演示视频脚本.md` |

## 7. 著作权

参赛团队自主开发部分著作权归参赛团队所有。
