# 麒麟高级服务器操作系统 V11 与 LoongArch 平台运维要点

## 麒麟高级服务器操作系统 V11 基础
麒麟 V11（Kylin Advanced Server）基于自主内核与国产生态，使用 systemd 管理服务，软件包管理常用 dnf/yum。查看系统版本：cat /etc/kylin-release 或 cat /etc/os-release。服务管理统一通过 systemctl status/start/stop。日志统一汇聚到 journald，使用 journalctl 查询。

## LoongArch 架构注意事项
LoongArch（龙芯）为自主指令集架构，部署需选择 loong64 架构的软件包与基础镜像。容器镜像须使用 loong64/loongarch64 标签。编译型组件需确认已提供 LoongArch 原生版本，避免 x86 二进制无法运行。JVM 推荐使用适配 LoongArch 的 OpenJDK 发行版。

## 服务自启与资源限制
使用 systemctl enable 设置开机自启；通过 systemd 的 LimitNOFILE、MemoryMax 等限制资源。建议为运维代理创建独立低权限用户 opsagent，配合 sudoers 精确授权，避免直接使用 root。

## 磁盘与文件系统巡检
使用 df -h 查看挂载点使用率，du -sh 排查大目录。麒麟默认根分区告警阈值建议 85%。清理前先用 du 定位，再删除明确可回收的日志或缓存，禁止对 / 等关键路径递归删除。
