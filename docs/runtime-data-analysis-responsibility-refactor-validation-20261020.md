# Runtime OS 数据分析职责重构与真实验收记录

## 版本与提交

- 改造前基线：`1da22672a`，标签 `v1.1`
- DATA_ANALYSIS 职责重构：`a0d7c92d8`
- Coding 报告证据约束：`c387c6105`
- Coding 产物自审：`74d6c6d8e`
- 只读产物授权：`5496a8ec3`
- 复核路径局部代码修复：`c6bf9effa`
- 产物读取审计与覆盖检查：`e3e0e1f88`
- 字段声明语义保护：`6ed6dbf59`

## 已落地职责边界

DATA_ANALYSIS 在受控模板数据到达后不再进入 Java 的 `InterpretationPlanEvidenceAnalyzer`、`ModelNativeAnalysisHarness` 和 refinement 内容分析循环。Java 保留任务分类、模板授权、参数绑定、批量执行、不可变快照、受控补数、结构校验和最终发布；Node Coding Agent 负责数据探索、分析方法、Python 程序、技术修复、结论和证据；Python Sandbox 仍是唯一代码执行通道。

关键改动位置：

- `InterpretationAnalysisSession`：DATA_ANALYSIS 从受控取数直接进入 Coding-owned coverage。
- `AnalysisCoverageCoordinator`：新增主 Coding 分析分支，旧 Harness 继续服务 QA 和兼容任务。
- `AnalysisCodingPort`、`CodingAnalysisService`：一次性交付多数据集、统一任务契约、受控 `data_request`、模型配置和 Trace。
- `AnalysisFinalizationPolicy`、`FinalSynthesisNode`：结构与授权来源通过后直接发布 Coding 报告，不调用 Java 模型做内容评审或改写。
- `ClineCodingAdapter`、`ClineRuntimeModelBridge`、`ClineModelCallbackController`：允许只读 `artifact_read`，且只能读取当前租户、当前运行、当前 Coding 任务归档的完整单行结果。
- `coding-agent-node/src/runner.js`：复核路径保留精确代码补丁，执行成功后读取归档结果再结束，并记录 `artifact_read` 开始/完成事件。

Node 仍无 MCP、数据库、宿主 Shell、网络或任意文件权限；模板选择和参数绑定未交给模型。代码中未增加客户、证券、字段名或指标公式硬编码。

## 测试结果

- Java 重构相关回归：116 项，1 项跳过；后续门禁专项 41/41、30/30、23/23 通过。
- Node Coding Agent：最终 41/41 通过。
- 覆盖的关键门禁包括跨任务产物拒绝、单行完整结果读取、未授权工具关闭、局部修复哈希校验、成功产物后读取复核、取消和恢复。

## 改造前后真实性能

测试使用真实模型、真实模板和真实数据。基线只有 2 个可完整提取样本，改造后性能组为 3 个样本，因此 P95 仅表示观测样本分位数，不是稳定统计尾延迟。

| 指标 | v1.1 基线 | Coding-owned 快路径 | 变化 |
|---|---:|---:|---:|
| 端到端 P50 | 827,991 ms | 448,601 ms | -45.8% |
| 端到端 P95 | 998,004 ms | 484,514 ms | -51.5% |
| Java 模型调用 P50 | 14.5 | 9 | -37.9% |
| Java 模型耗时 P50 | 692,433 ms | 338,322 ms | -51.1% |
| Coding 模型调用 P50 | 1.5 | 1 | -33.3% |
| 模板批次耗时 P50 | 6,197 ms | 5,257 ms | 非主要瓶颈 |
| Python 往返 P50 | 4,252 ms | 4,469 ms | 非主要瓶颈 |
| 首次执行成功率 | 100% | 100% | 持平 |

三个改造后 Trace 均未出现 `ModelNativeAnalysisHarness` 调用。主要剩余耗时是 Java 上游需求理解、规划和参数绑定模型，以及启用 Coding 自审后的额外模型回合。

## 客户 070200046604 真实验收

最终部署候选的真实任务状态为 `SUCCEEDED`：

- 端到端：518,657 ms
- 首批模板前规划：280,648 ms
- 模板批次：1 次，2,316 ms
- Java 模型：5 次，137,436 ms
- Coding 模型：3 次，221,855 ms
- Python：1 次，3,245 ms
- Coding 输入/输出 Token：38,917 / 13,675
- Java 语义复核：0 次
- `artifact_read`：开始 1 次、成功 1 次

资产、持仓市值、资金余额、成交金额和持仓明细可追溯到授权数据；总资产 847,174.25、资金 912.05、证券市值 846,262.20、账户当日盈亏 42,263.81、持仓当日盈亏合计 40,563.24 均被计算出来。任务也识别了 1,700.57 的口径差异、委托与成交笔数范围差异，并未把 qcfx 的 2 条覆盖直接判为数据错误。

本次内容正确率门禁仍未完全通过：最终报告在一处勾稽表中对调了账户表和证券余额表的当日盈亏来源；买入清算资金仍使用了错误方向字段并显示为 0；个别字段的业务展示名仍需以模板声明为准。运行成功、结构校验和 Coding 自审不能替代这些逐项口径核对，因此该样本不能计为“业务结果完全正确”。

## 部署

- API：`runtime-os-api:protocol-recovery-87c4b3d27a0e`
- Coding Agent：`runtime-os-coding-agent:analysis-fast-461e165ad657`
- 部署脚本确认前端字节未变化、其他容器未重启、网关原地 reload。
- 服务器证据目录：`/opt/runtime-os-docker-stage/evidence/responsibility-refactor-20261020`
- 本地证据包：`target/responsibility-refactor-final-evidence.tar.gz`

## 剩余瓶颈与下一步

1. 普通任务仍未达到 3–5 分钟目标。模板执行仅约 2–6 秒，主要瓶颈是 Java 规划模型和 Coding 模型，不能通过并发更多模板解决。
2. Coding 自审路径提高了可追溯性，但当前模型对字段声明语义仍有随机错误。下一步应把通用指标来源和派生公式提升为 Coding 输出契约中的结构化 provenance，再由 Node 在完成前执行一致性检查；Java 只验证结构和授权引用，不能恢复为语义裁判。
3. 应按复杂度选择更强的 Coding 模型并重复真实样本，分别统计一次程序成功率与一次业务口径通过率。Prompt caching 遥测在现有供应商回执中未稳定记录，不能宣称命中率提升。
4. 当前性能数据包含真实供应商波动，正式 P50/P95 需要至少 20 个同版本成功样本，并单独记录失败和取消样本。
