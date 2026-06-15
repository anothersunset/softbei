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
# 浏览器打开 http://<服务器IP>:8080/
```
默认以 `mock` 推理 + `dry-run=true` 运行，无需外部依赖即可演示全链路。

## 三、容器化部署
```bash
# 在项目根目录
docker compose -f deploy/docker-compose.loong64.yml up -d --build
```

## 四、接入国产模型
修改 `application.yml` 或环境变量：
```yaml
ops:
  llm:
    provider: deepseek
    base-url: https://api.deepseek.com
    model: deepseek-chat
    api-key: <YOUR_KEY>
```

## 五、启用真实执行与最小权限（生产）
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
