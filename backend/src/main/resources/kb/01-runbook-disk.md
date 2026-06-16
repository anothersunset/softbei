# 常见故障 Runbook：磁盘空间不足

## 磁盘满 / 空间不足 处置流程
1）df -h 确认告警挂载点与使用率；2）du -x --max-depth=1 逐层定位大目录；3）优先清理应用滚动日志、临时文件、过期缓存；4）删除前确认文件不再被进程占用（lsof）；5）清理后复查 df -h。注意：禁止 rm -rf / 或对 /var/lib/mysql、/var/lib/pgsql 等数据目录执行删除，应通过业务侧归档或扩容解决。

## 日志膨胀治理
journald 日志可用 journalctl --vacuum-size=200M 限制；应用日志应配置 logrotate 按大小/时间轮转。临时清理单个滚动日志文件属于变更操作，需人工确认。
