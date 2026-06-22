# Notion → 本地 → 测试 → GitHub 自动化工具

## 快速开始

### 方式一：单次同步（推荐新手）

```bash
# 用法: ./notion-once.sh <Notion页面ID> [目标文件路径]
./notion-once.sh abc123def456... src/main/java/com/zhiqian/ops/MyCode.java
```

### 方式二：持续监听（推荐日常开发）

```bash
# 用法: ./notion-sync.sh <Notion页面ID> [目标文件路径] [检查间隔秒数]
./notion-sync.sh abc123def456... src/main/java/com/zhiqian/ops/MyCode.java 10
```

## 获取 Notion 页面 ID

1. 在浏览器打开你的 Notion 页面
2. 查看 URL，格式如：
   ```
   https://www.notion.so/My-Page-abc123def456789...
   ```
3. 最后的 32 位字符就是页面 ID：`abc123def456789...`

## 工作流程

```
┌─────────────────┐
│  Notion AI 写代码  │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  notion-sync.sh  │ ← 每 10 秒检查一次
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   保存到本地文件   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   mvn compile    │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    mvn test      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  git push GitHub │
└─────────────────┘
```

## 常见问题

### Q: 如何停止监听？
按 `Ctrl+C` 即可停止。

### Q: 编译失败怎么办？
脚本会显示编译错误信息，你需要在 Notion 中修复代码，然后重新同步。

### Q: 测试失败怎么办？
脚本会显示测试错误信息，你需要在 Notion 中修复代码，然后重新同步。

### Q: 如何同步多个文件？
运行多个监听实例，每个实例监听不同的 Notion 页面：

```bash
./notion-sync.sh page-id-1 src/main/java/com/zhiqian/ops/File1.java &
./notion-sync.sh page-id-2 src/main/java/com/zhiqian/ops/File2.java &
```

### Q: 如何查看同步历史？
查看 Git 提交记录：

```bash
git log --oneline | grep "从 Notion 同步"
```

## 高级配置

### 修改默认目标文件

编辑 `notion-sync.sh` 或 `notion-once.sh`，修改：

```bash
TARGET_FILE=${2:-"src/main/java/com/zhiqian/ops/NewCode.java"}
```

### 修改检查间隔

```bash
# 每 5 秒检查一次
./notion-sync.sh page-id file.java 5

# 每 30 秒检查一次
./notion-sync.sh page-id file.java 30
```

### 只同步不测试

如果你想跳过测试，直接推送：

```bash
# 获取内容
NOTION_KEYRING=0 ntn pages get <page-id> > code.java

# 提交推送
git add code.java
git commit -m "sync from notion"
git push
```

## 注意事项

1. **确保 Maven 已安装**：脚本使用 `mvn` 命令
2. **确保 Git 已配置**：需要有 GitHub 推送权限
3. **网络连接**：需要能访问 Notion API 和 GitHub
4. **文件路径**：目标文件路径相对于 `backend/` 目录
