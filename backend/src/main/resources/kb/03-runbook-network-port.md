# 常见故障 Runbook：网络与端口占用

## 端口占用 / 连接异常 处置流程
1）ss -tlnp 或 netstat -tlnp 查看监听端口与对应进程；2）定位占用目标端口的 PID 与服务；3）若为冲突服务，先停止对应 systemd 服务而非直接 kill；4）检查防火墙放行（firewalld/iptables/nft）。只读排查命令（ss、netstat）可安全执行。

## 网络连通性排查
使用 ping、ss、ip addr、ip route 等只读命令排查链路与路由；修改 iptables/nft 规则属于高危变更，需人工确认并具备回滚预案。
