# 龙芯麒麟 V11（LoongArch）部署与 L3 取证 Runbook

> 面向「自主指令系统 LoongArch + 麒麟高级服务器版 V11」的部署与 L3 级联故障证据采集。

## 0. 关键结论

| 项 | 结论 |
|---|---|
| 应用 | 纯 Java / Spring Boot，无 JNI，字节码与依赖均架构无关 |
| 安装包 | **仅源码 + pom.xml（依赖声明），不打进任何第三方依赖** |
| 依赖 | 部署时 `mvn` 按 pom.xml 从 Maven 镜像联网下载（与 PyPI/npm/apt 同理） |
| 运行 | loongarch64 JDK17；`mvn package` 产出 jar 后 `java -jar` |

## 1. 打包（合规：不含依赖）

安装包 = 源码树，**排除** `target/`、本地 `.m2`、任何依赖 jar：
```bash
# 本机生成精简源码包（仅数 MB）
git archive --format=zip -o opsguard-src.zip HEAD
# 或手动 zip，排除 target/ .git/
```
依赖全部声明在 `backend/pom.xml`，不随包分发。

## 2. 传输到麒麟 VM

- VM 操作界面「上传」`opsguard-src.zip`（首选）
- 或邮箱 / 网页版微信 → VM 下载
- 解压：`unzip opsguard-src.zip -d ~/softbei`

## 3. 部署（依赖联网下载）

```bash
# 3.1 装工具链（麒麟 yum 源）
sudo yum install -y java-17-openjdk-devel maven

# 3.2 配 Maven 镜像（LoongArch 上加速，~/.m2/settings.xml）
#   <mirror><id>aliyun</id><mirrorOf>*</mirrorOf>
#   <url>https://maven.aliyun.com/repository/public</url></mirror>

# 3.3 构建（依赖从镜像下载，不在包内）
cd ~/softbei/backend && mvn -q clean package -DskipTests

# 3.4 起服务 + 健康检查
nohup java -jar target/*.jar > /tmp/opsguard.log 2>&1 &
curl -sf http://127.0.0.1:8080/api/ops/runtime && echo OK
```

> 顺带验证：去掉 `-DskipTests` 跑全量可证明 **190 单测在 LoongArch 全绿**（国产化适配加分项）。

## 4. L3 证据取证

```bash
sudo REPO=~/softbei bash backend/scripts/collect-l3-evidence-kylin.sh
```
自动：构建 → 起服务 → 压满根分区 ≥90%(disk CRITICAL) → 5min 内注入同源 DISK_FULL/IO 日志 → 调 `/api/ops/rca` → 核对 `overallLevel=L3` → 存 `rca-evidence/l3-cascade-kylin.json`。

> **L3 触发条件**（`CrossSourceRca.grade`）：`metricCritical && (logElevated || kindMatch)`。缺 root / 不同机 / 磁盘未到 90% → 仅 L2。

## 5. 回填模板（跑出后填真实值，绝不预填）

| 字段 | 值 |
|---|---|
| 环境 | 龙芯 LoongArch + 麒麟 V11 |
| inspectId | _____ |
| traceId | _____ |
| overallLevel | L3 |
| disk-usage | __% CRITICAL |
| 同源日志 | DISK_FULL + IO @ _____ |
| 置信度 | __% |

跑出后把 `l3-cascade-kylin.json` 贴回，将据此：① 更新 docs/19 矩阵补「麒麟 LoongArch 实跑」行 ② README 验收证据索引补条目 ③ 归档 JSON。

## 6. 排障

| 现象 | 处理 |
|---|---|
| 无 java/mvn | `sudo yum install -y java-17-openjdk-devel maven` |
| 依赖下载慢/失败 | 配阿里云 Maven 镜像（见 3.2） |
| 服务起不来 | `tail -n 50 /tmp/opsguard.log` |
| 只到 L2 | df 确认≥90% / journalctl 确认日志 / root+同机 |
| 无 jq | 脚本自动回退 grep，不影响判级 |
