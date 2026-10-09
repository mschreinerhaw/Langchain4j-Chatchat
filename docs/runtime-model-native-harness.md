# Model Native Analysis Harness

原则：Model decides. Runtime executes. User judges.

## 本轮联机问题定位

2026-10-09 捕获了两次实际执行：

- `c22c105b-be5a-4c1c-a155-b658a8b4be03`：三只股票的投资机会比较，3 个数据集进入 Worker 路径，134 条事件，最终为 PARTIAL_SUCCESS。
- `665df6cf-2875-4556-a09e-531561958ad5`：同三只股票的行情分析，1 个大数据集也因数据体积进入 Worker 路径，94 条事件，最终为 PARTIAL_SUCCESS。

这两个案例的数据源均有成功返回。旧路径将数据规模与分析职责绑定，经过 Worker 结构化 Claim、语义分类及二次汇总。代码还存在强制逐数据集 findings 覆盖和方法检查后的模型修复。最终正文可被保留，并不代表它生成前的上下文与决策已摆脱这些机制。

修改目标是消除这层架构干预、恢复完整证据访问及模型自主表达。报告结论的正确性仍需用户结合实际来源判断；不以 Runtime 评分宣称报告质量已经得到证明。

## 本轮实现范围

`AnalysisCoverageCoordinator` 在数据集绑定后选择 `ModelNativeAnalysisHarness`。该路径绕过 Worker Claim 提取、固定关系推断、业务洞察生成、逐数据集 findings 修复及方法修复。原路径保留为配置回退。

模型上下文分为当前问题及模型工作状态、授权 Skills/Agent 角色/领域知识及可用能力、证据导航视图与执行回执。较大的证据仍由既有 DatasetHandle/SpillStore 承载；投影省略不等于源证据不存在。

模型可直接输出最终 Markdown，或使用 `model_native_analysis.v1`：

```json
{
  "schemaVersion": "model_native_analysis.v1",
  "completed": false,
  "reportMarkdown": "已形成的分析正文",
  "workspace": { "notes": "模型自己的工作笔记", "artifacts": [] },
  "evidenceRequests": [
    { "operation": "READ_TEXT", "datasetReference": "已绑定的数据集 ID", "record": 1,
      "field": "已有文本字段", "fromChar": 0, "maxChars": 3000 }
  ]
}
```

可执行能力复用已有 READ_RECORDS、READ_NESTED_RECORDS、READ_CONTEXT、CALCULATE、EXECUTE_OPERATION，并增加 READ_TEXT 原文游标。READ_TEXT 返回精确片段、totalChars、hasMore、nextChar，模型可以继续读取同一原始字段，无需另一个模型提取器。计算只接受来源声明的合同或操作；Runtime 不自行选择业务公式。

workspace 由模型维护；Runtime 保存版本、上下文指纹、输出检查点及读取结果，限制资源和隔离范围。相同租户/运行及完全相同输入指纹的输出检查点可复用；不同输入不能命中。记录数准备完成只表示执行事实，不表示每条记录都被模型语义审阅。

最终发布复用本次模型正文，不再调用第二次总结模型、不追加分析质量门禁、不在正文追加 Runtime 覆盖统计。来源失败和 sourceComplete=false 仍保留在执行元数据中。

第一次部署后的联机重跑还定位到 AnswerEvidenceAuditService 的遗留 Claim 评分：新路径已生成正文，最终装配层仍追加质量提示、影响公共执行状态。新路径现在在该边界明确跳过 Claim 评分，审计信封保留工具执行与数据集引用，不把人工价值判断作为运行失败或强制复核状态。旧路径保持原有兼容行为。

图形能力继续从注册表动态注入。只有模型实际提供 ReportBlock 建议时，才运行独立的确定性绑定节点；字段、数据引用和权限校验失败只移除相应图形。无建议时不强制图表，也不新增图形规划模型调用。

## 配置与观察

```yaml
chatchat:
  agent-runtime:
    model-native-harness-enabled: true
    harness-max-model-turns: 8
```

轮次限额为资源边界，可配置 1–64；不再受旧路径最多三次决策限制。每轮最多四次有界证据请求，READ_TEXT 每次最多 3000 字符。超出预算时保留模型已有报告；无正文则报告执行未产生结果。协议或读取失败反馈给模型，不把读取失败解释为业务证据不存在。

关注元数据：modelNativeHarnessActive、harnessWorkspace、harnessTrace、harnessModelCalls、harnessStopReason、harnessSemanticCoverage、analysisReportGenerationMode。前端通过 HARNESS_TURN 逐轮呈现模型分析与证据访问。

这是第一阶段的证据导航和上下文承载改造。外部工具发现、搜索与 MCP 授权仍沿用现有 Agent Loop；尚未将整个执行系统迁移成统一的自由工具循环，也没有宣称已完成全部 Artifact SDK、用户反馈持久化或跨运行记忆。

## 验证

回归覆盖：原文窗口连续读取、工作状态传递、越界数据引用拒绝、模型直接结束、超过旧三轮限制、预算耗尽保留正文、多数据集不触发旧 Worker/业务推断、数据源失败保持可见、总结发布不再调用模型、无图形建议不强制规划、前端每轮事件不合并。

本轮相关后端回归 131 项通过，前端回归 104 项通过，构建通过。

2026-10-09 最终联机任务 `30cc170e-f05a-4145-a14d-37794a4e9f29` 使用原三只股票比较问题及 qwen3.7-plus，状态 SUCCESS，42 条事件。Skills 为 APPLIED，数据工作区绑定 6 条实际返回记录；报告分析模型调用 1 次、Worker 0 个、最终总结模型调用 0 次。模型自主选择直接结束，本次没有额外原文读取请求，也没有图形建议，因此未运行图形规划节点。

模型草稿与发布契约正文完全相同（2175 字符）；最终装配只进行了现有 Markdown 行尾空白规范化，规范化后的正文 SHA-256 一致。最终结果没有 Claim 覆盖分值、逐条 Claim 审核或 Runtime 质量提示。SUCCESS 表示执行完成，不表示平台认证了模型的业务结论。

生产前端使用该实际任务结果进行浏览器内回放，未写入或替换服务器会话：查询明细默认折叠，摘要文字 12px 灰色；未生成启发式图表；页面无 JavaScript 异常。截图、任务、事件、运行元数据、完整报告及验证指标保存在 `target/codex-live/harness-20261009/`。其中 `final-*.json` 是最终实测，`after-*.json` 是第一次部署后暴露发布层遗留评分的实测，`comparison-*`/`market-*` 是修复前样本。

最终 API 进程 PID 为 1202866，部署包 SHA-256 为 `3fd49178d57bcfe001519edc24987452fe5a389a5412af4e0e8f36be62ab5edc`。更新前原包保留在 `/opt/chatchat-deploy-backup/harness-20261009/chatchat.jar`。如需回退分析路径，可设置 `chatchat.agent-runtime.model-native-harness-enabled=false` 后重启。

扩展检查发现两个旧路径问题：InterpretationPlanRuntimeArchitectureTest 的主文件行数上限为 8730，而当前 HEAD 已超过该值；AgentAnswerFinalizerEvidenceAnswerTest 的 reviewerTimeoutUsesConfiguredModelTimeout 实际等待约 5 秒，未满足小于 3 秒的断言。本轮未修改对应执行器主文件或旧 reviewer 超时逻辑，也未放宽门槛。新路径不调用该 reviewer。扩展失败日志保留在 target/codex-live 与 target/harness-tests-extended-20261009.log；最终部署记录见联机记录。
