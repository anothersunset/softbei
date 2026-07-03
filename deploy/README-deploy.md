# 部署说明（LoongArch + 麒麟高级服务器版 V11）

本项目为 B/S 架构，后端单一 Spring Boot 进程同时提供：
- REST API：`/api/ops/**`
- MCP JSON-RPC：`/mcp/rpc`
- 静态控制台：`/`（`backend/src/main/resources/static/index.html`）

## 一、环境要求
- 架构：LoongArch (loong64)
- 操作系统：麒麟高级服务器版 V11
- JDK 17（推荐国产 欕峰 BiSheng JDK / Loongson JDK）
- （可选）国产开源模型：DeepSeek / Qwen3

## 二、本地构建与运行
```bash
cd backend
mvn -B clean package -DskipTests
java -jar target/ops-agent-1.0.0.jar
# 浏览器打开 http://127.0.0.1:8080/
```
默认以 `mock` 推理 + `dry-run=true` 运行，无需外部依赖即可演示全链路。

## 三、信任边界与入口鉴权

默认配置绑定 `127.0.0.1`，仅用于本机演示。内网或公网部署时需显式开放绑定地址，并为 REST 与 HTTP MCP 入口启用同一个令牌；若绑定到非回环地址但未配置令牌，服务会拒绝启动：

```bash
export OPS_BIND_ADDRESS=0.0.0.0
export OPS_API_TOKEN=<强随机令牌>
```

启用后调用 `/api/**`、`/mcp/**` 与 `/actuator/**` 必须携带：

```http
X-Ops-Token: <强随机令牌>
```

或：

```http
Authorization: Bearer <强随机令牌>
```

## 四、容器化部署
```bash
# 在项目根目录
docker compose -f deploy/docker-compose.loong64.yml up -d --build
```

## 五、接入国产模型
修改 `application.yml` 或环境变量：
```yaml
ops:
  llm:
    provider: deepseek
    base-url: https://api.deepseek.com
    model: deepseek-chat
    api-key: <YOUR_KEY>
```

## 六、启用真实执行与最小权限（生产）
```bash
sudo OPS_USER=opsagent bash deploy/scripts/setup-ops-user.sh
```
随后设置：
```yaml
ops:
  exec:
    dry-run: false      # 谨慎：关闭后变更类命令会真实执行
    use-sudo: true
    run-as-user: opsagent
```
> 安全双重防护：应用层 `IntentRiskGuard` + OS 层 `sudoers` 白名单，两道防线互为冗余。

## 七、审计取证兜底（可选）

仓库提供 `deploy/auditd-rules.conf` 作为 auditd 参考规则，覆盖账号、sudoers、systemd 配置和数据库数据目录等关键路径：

```bash
sudo install -m 0640 deploy/auditd-rules.conf /etc/audit/rules.d/opsguard.rules
sudo augenrules --load
```

注意：auditd 只负责事后取证，不负责阻止破坏。预防性控制仍来自应用护栏、最小权限账号、sudoers 白名单、ACL/只读挂载等 OS 层配置。
