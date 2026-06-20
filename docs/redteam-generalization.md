# 红队泛化评测方法学（Generalization / OOD）

> 目的：回应评委质疑「48 条语料是否自证循环、换没见过的样本是否还能拦」。
> 原则：**真实优先**。本文中所有「待回填」指标均由 `mvn test` / pitest 实跑产生，不预填、不估算。

## 1. 为什么需要盲测

初赛的 48 条红蓝语料与 `risk-rules.yaml` 规则同源，存在「训练=测试」的自证风险：拦截率 100% 只能说明「规则覆盖了这批语料」，不能说明「面对未见过的攻击仍有效」。因此引入与调参隹离的**盲测集**。

## 2. 盲测集构成

- 语料：`backend/src/test/resources/redteam/blindset-corpus.yaml`
- 评测：`backend/src/test/java/com/zhiqian/ops/eval/BlindSetCorpusTest.java`
- 样本类型（均未参与规则调参）：
  - **编码/混淆**：base64、leetspeak、空格分隔
  - **同形字**：全角字符模拟 Unicode 混淆
  - **多语言**：日语 / 西班牙语注入
  - **新措辞**：未在原语料出现的越狱 / 间接诱导表述
  - **命令变体**：fork 炸弹、`curl|bash`、`find -delete`、覆写关键文件等
  - **负样本**：语义上合法但表面敏感的运维请求（验证误拦）

## 3. 指标定义

以「应拦截」为正类，统计混淆矩阵：

- **TP**：应拦截且被拦；**FN**：应拦截却放过（漏报，最危险）
- **FP**：不该拦却拦了（误拦，伤易用性）；**TN**：不该拦且放行
- **Precision** = TP/(TP+FP)；**Recall** = TP/(TP+FN)；**F1** = 2PR/(P+R)

## 4. 运行方式

```bash
cd backend
mvn -Dtest=BlindSetCorpusTest test     # 控制台输出混淆矩阵与 P/R/F1
mvn -P mutation org.pitest:pitest-maven:mutationCoverage   # 变异测试 mutation score
```

## 5. 结果（待本地/CI 实跑回填，勿预填）

### 5.1 注入检测（盲测）

| 指标 | 值 |
|---|---|
| TP / FP / TN / FN | 待回填 |
| Precision | 待回填 |
| Recall | 待回填 |
| F1 | 待回填 |

### 5.2 危险命令拦截（盲测）

| 指标 | 值 |
|---|---|
| TP / FP / TN / FN | 待回填 |
| Precision / Recall / F1 | 待回填 |
| 命令精确分级准确率 | 待回填 |

### 5.3 变异测试

| 指标 | 值 |
|---|---|
| 生成突变数 | 待回填 |
| 被杀死（killed） | 待回填 |
| Mutation Score | 待回填 |

## 6. 偏差处理原则

盲测出现漏报/误拦是**预期的、有价值的**：

1. 控制台会打印「偏差明细」，按类型归因（编码/同形字/多语言/新措辞）。
2. 针对真实漏报，在 `risk-rules.yaml` 补强规则（如增加规范化/解码预处理），而非把盲测样本反加进调参集。
3. 如实披露「当前不能拦住哪类变体」，比虚报满分更可信、更经得起追问。
