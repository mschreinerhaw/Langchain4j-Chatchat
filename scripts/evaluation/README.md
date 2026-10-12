# 固定证据的分析方法评测

本目录只用于离线比较模型与分析 Skill。生成和评审都不接入线上交付状态，不新增 Runtime 结论拦截规则。模型交付前自查属于 Skill 工作方法；评测用于检验它是否实际有效。

`fixed-evidence-analysis.py` 将同一任务、同一份冻结材料分别交给原指导和新指导，支持多个模型、多次重复。模型生成时不提供评分规则或参考答案。保存请求、原始回答、输入摘要、用量、终止原因、延迟与失败记录，并生成匿名 A/B 评审材料。不要将接口失败当作模型质量得分。

输入格式：

```json
{
  "id": "anonymous-case-id",
  "question": "同一原始任务",
  "system": "所有对比组共享的任务边界与输出要求",
  "evidence": {"records": [], "provenance": "冻结授权材料的来源"}
}
```

原始客户材料、正文和生成结果存放在租户隔离存储或本地忽略的 `target/` 目录，不提交仓库。真实快照可使用明确记录的投影去除重复字段，但不能只为某一对比组补充材料、标签或参考答案；报告必须说明该评测是否使用了投影，以及与完整 Harness 运行的差异。

生成命令（Python 3，无第三方 Python 依赖）：

```sh
python3 scripts/evaluation/fixed-evidence-analysis.py \
  --fixture /private/fixture.json \
  --baseline /private/baseline.md --guided /private/guided.md \
  --models configured-model-a configured-model-b --repeats 3 \
  --output /private/results --workers 2 -- provider-adapter
```

适配器从标准输入读取 `{model,messages:[{role,content}]}`，标准输出仅返回 JSON `{answer,finishReason,tokenUsage}`；日志写到标准错误，避免凭证泄漏。默认不把标准错误保存在评测结果。适配器允许读取现有发布模型连接，不得修改平台默认模型或凭证。

`PublishedModelAnalysis.java` 是部署环境的只读适配器，使用实际依赖中的模型工厂及发布表，凭证只在内存中解密。使用部署包解出的依赖编译，不启动 Spring 应用；配置目录及 YAML 文件作为参数传入，使用配置声明的数据库文件导入。相对密钥路径按应用根目录解析。不要在仓库、评测输出或命令行保存密钥。适配器没有工具或取数能力；只调用已发布模型。公共调用预算为 8192 输出 token、600 秒请求上限、零自动重试；采样参数使用现有模型工厂默认值，不将默认值宣称为确定性生成。

```sh
javac -cp '/private/deployed-libs/*' scripts/evaluation/PublishedModelAnalysis.java
java -cp 'scripts/evaluation:/private/deployed-libs/*' PublishedModelAnalysis \
  /private/application/config application.yml,application-dev.yml
```

评审检查项：

| 检查项 | 要核对的内容 |
| --- | --- |
| 算术正确 | 根据引用原值复算涨幅、倍数、合计和单位换算；识别近似值与舍入误差 |
| 计算可核验 | 重要计算是否给出原值、公式或清晰计算关系、单位、起止日期和来源 |
| 比较口径 | 是否使用共同日期与区间；不同日期是否仅并列展示；PE/复权/成交单位是否一致 |
| 事实与推断 | 是否把资金身份、净流出、承接深度、因果或公司整体基本面写成无依据事实 |
| 正文与局限 | 是否一面承认缺失数据，一面在正文给出依赖该数据的确定性结论 |
| 有效完成任务 | 是否完成证据能支持的分析，而不是泛化免责声明或完全拒绝分析 |

每项标记通过/失败/不可评，并记录引用和理由。缺失重要计算不能算术判错，也不能靠不做计算得到高质量评价：同时评估计算依据和任务完成度。正确性、完整性、引用准确性和可用性仍按仓库 `docs/answer-quality-blind-evaluation.md` 的分维度规范评审。

正式质量对比应使用至少两名独立评审和匿名材料，分歧复核。`private-pair-mapping.json` 只供评审结束后回映射；不能交给评审。一次单样本探索审阅不得声称为正式盲测或稳定胜率。报告列出模型与方法每组的样本数、逐项通过数、错误实例、P50/P95 延迟和调用失败，说明样本、投影、提示长度与模型随机性限制。改善未达到统计可靠性时明确说明；后续增加不同案例和保留测试集，避免围绕单一错误报告过拟合。

可用的领域指导正文见 `database/skills/market-analysis-review.md`。由既有 Skill 内容管理接口更新正文，使用原名称、分类、描述和授权配置；更新前备份并检查并发变化。不要通过改变通用系统提示词或线上质量裁决来代替分析方法评测。
