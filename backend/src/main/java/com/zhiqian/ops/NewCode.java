---
title: ''
---

# OpsGuard 代码工作区
> 在这里用 Notion AI 写代码，脚本会自动同步到本地并测试
## 使用方法
1. **在下方写代码**（或让 Notion AI 帮你写）
2. **保存后**，脚本会自动检测变化
3. **自动同步到本地** → 编译 → 测试 → 推送 GitHub
## 代码区域
```java
// 在这里写你的 Java 代码
// 例如：
package com.zhiqian.ops;

public class NewCode {
    public static void main(String[] args) {
        System.out.println("Hello from Notion AI!");
    }
}
```
## 同步命令
```bash
# 单次同步
./notion-once.sh 38717a8a6c0281a8aadfe24221c15ced src/main/java/com/zhiqian/ops/NewCode.java

# 持续监听
./notion-sync.sh 38717a8a6c0281a8aadfe24221c15ced src/main/java/com/zhiqian/ops/NewCode.java 10
```
## 注意事项
- 代码块中的内容会被同步到本地
- 确保代码语法正确
- 测试失败会显示错误信息
- 测试通过会自动推送到 GitHub
