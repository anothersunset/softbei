# Step 3 + Step 6 完成回填 (2026-06-23)

## Step 3: 测试归类命名重构 ✅

**RedTeamCorpusTest.java** 已拆分为 `@Nested` 分组：

```
RedTeamCorpusTest
 ├─ @Nested InjectionDetection
 │   ├─ should_block_when_roleHijackInjection (17/17 PASS)
 │   └─ should_pass_when_benignLooksLikeInjection (6/6 PASS)
 ├─ @Nested CommandInterception
 │   ├─ should_block_when_redlineCommand (10/10 PASS)
 │   └─ should_pass_when_readOnlyCommand (8/8 PASS)
 └─ should_reportFullCorpusMetrics (48/48 综合指标)
```

**CrossSourceRcaDepthTest** 方法名已遵循 `should_<expected>_when_<scenario>` 规范：
- `critical_disk_with_recent_disk_full_log_escalates_to_l3_with_evidence_chain`
- `critical_load_with_dependency_and_network_logs_produces_l3_dependency_insight`
- `config_drift_logs_without_resource_metric_stay_l2_and_keep_boundary_honest`

40e3808fe861621a72eadfe5340d6aa4 --- 全部 184 测试 PASS，BUILD SUCCESS

## Step 6: 4 处文档不一致修正 ✅

| # | 修正项 | 涉及文件 |
|---|--------|---------|
| ① | 单元测试数 33→184 | README, 08-PPT, 04-功能测试, 云验收报告, 回填清单 |
| ② | asciinema 链接统一 | 回填清单 → `l3xgq0gG6mENBG85dfCKsMrKq` |
| ③ | RCA 故障类型 3→6 | README, 18-演进路线, 17-本地测评, 回填清单 |
| ④ | PPT 测试数同步 | 08-演示PPT大纲 |

## 待 Notion 页面同步

更新页面 `d1baa5a0c9c74cd1bc6c1b748beed98f` 中：
- Step 3 行：🟡 → ✅，描述改为"已完成 @Nested 分组 + should_预期_when_场景 命名"
- Step 3 ②节：🤖 方案待应用 → ✅ 已应用
- Step 6 行：描述改为"✅ 已完成，4 处不一致已修正并推送到仓库"
- Step 6 表格 #①②③④：状态全部标记 ✅
