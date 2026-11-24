# Cline 数据分析工具结果治理实施记录

基线 `4885fe536`。同一业务基线的供应商实测继续参考 [第一阶段审查](cline-data-analysis-audit.md)。

## 接入与规则

Java `NativeAnalysisBridge.execute` 在创建分析任务时把服务器生成的 `data-supply-manifest.json` 精确路径写入冻结运行上下文。 `NativeS3CodingAdapter.binding` 要求该路径实际存在于冻结输入列表内，并放进签名授权。Broker 再验证 Silo 清单，受限执行器将其交给 Native Server。历史任务和其他用途任务沿用原有契约。

`cline-agent-server/scripts/analysis-tool-results.mjs` 通过固定版本的 VSCode Session Host 接入已有 `beforeTool` / `afterTool`。上游 `afterTool` 位于工具执行后、工具消息写入历史前，保留原生调用 ID、状态、错误与历史管理。没有改动 Agent loop、压缩算法或供应商消息编排。[Cline 4.1.27 Session Host](https://github.com/cline/cline/blob/8075d2e35cf5cad67c24f180c80a05d311b74a3e/apps/vscode/src/sdk/vscode-session-host.ts) · [原生 AgentRuntime 工具边界](https://github.com/cline/cline/blob/8075d2e35cf5cad67c24f180c80a05d311b74a3e/sdk/packages/agents/src/agent-runtime.ts#L2636)

| 分析任务工具 | 模型可见内容 | 计算及证据 |
|---|---|---|
| `read_files` | 许可范围内的源代码、Skill、签名指定的数据清单、Silo 挂载清单和至多 4 KiB 的指定摘要。含受保护文件的混合批次返回说明。 | Python 继续使用原文件。输入、下载、明细和证据目录走程序读取。 |
| `search_codebase` | 引用式回执。该版本原生搜索无逐次路径参数，可跨工作区搜索，故分析模式的命中内容通过引用检查。 | Native 工具返回归档，普通代码任务保留原生搜索。 |
| `run_commands` | 引用、退出诊断、可识别的异常类型和源码位置。 | 命令继续由 Native Cline 执行，既有观察器另外记录 stdout/stderr、大小和截断状态。工具回执归档范围是原生工具实际返回。 |
| Runtime MCP | 至多 4 KiB 的结果沿用原回执，大结果返回引用。 | 原有下载、已发布模板快照、授权校验保持。归档范围受 MCP 投递限制。 |
| Skills、编辑器 | 沿用原生按需调用和源码修改。 | Python 数据分析 Skill 提供方法和示例，Cline 自主选择分析代码、执行顺序和修复策略。 |

引用包括路径、SHA-256、序列化结果字节数和归档状态。任务工作区归档限额为 32 MiB 或 512 项。归档失败或超限产生明确回执。完整 Silo 数据和既有命令记录独立保留。工具归档位于任务工作区；将其中的证据作为长期业务产物保留时，仍需使用既有交付选择。模型可读取摘要与证据位置，完整细节可用 Python 获取并写入产物。该行为约束依赖任务用途与文件契约，现有授权、隔离、Silo 哈希校验继续负责安全边界。

## 结果和日志

分析摘要可以写在 `output/analysis_summary.json`，必要时分别使用 `data_quality.json` 和 `execution_manifest.json`。记录数据集／快照、实际扫描数、有效及过滤后记录数、异常数、计算方法和证据位置。摘要超限时按专题编写紧凑摘要，完整结果按现有报告和 Silo 产物选择交付。现有报告 Schema 继续约束结果，业务指标和程序结构由 Cline 决定。

`python-data-analysis` 1.3.6 增加按需阅读的 [日志分析方法](../coding-agent-node/skills/python-data-analysis/references/log-analysis.md)。示例返回完整长记录中命中的行和字符区间。可用 Python 进一步提取 InnoDB 章节、异常时间、Java 堆栈特征或调度事件。程序处理证据全量，模型解释选出的指标和位置。

## 模型传输

Broker 回执分别记录响应头、首末响应体、首末 SSE JSON 数据、可识别的首个模型增量及最后响应体后的空闲耗时。HTTP 响应后连接重置记录为 `UPSTREAM_RESPONSE_STREAM_INTERRUPTED` 并保留原错误类别。断开的下游流会向上游传播取消，包含等待响应头和写入背压期间。既有重试与超时阈值保持，缺失供应商用量仍为缺报。响应头时长包括连接、调度和上游处理，单独 TCP/TLS 耗时和网关内部重试还需要上游或 HTTP 客户端追踪。

## 回归与发布

- 定向 Java 验证覆盖 20 个来源、万行分页和签发的精确清单路径。Node 定向回归包括工具结果、回执、签发、租户传输、Skills 和命令证据。Windows 不具备 Linux 所有权切换场景，相关用例由原测试按平台跳过。
- Python 示例执行了 1 万、10 万、100 万和 1,000 万行的 JSONL 逐行扫描，识别末行字段。独立用例覆盖空文件、错误 JSONL、行数不符及超过 10 万字符的首中尾日志证据。
- 固定 Cline 上游归档通过补丁锚点及源文件指纹校验，修改后的 Host 与 Hook 通过局部 esbuild 打包。原生整包构建、实际隔离节点运行和真实业务冷／热缓存复测仍待完成。当前 851,021 输入、723,072 缓存读和 20,702 输出是改造前数据。改造后供应商 Token、费用及完成率还需实测，再判断 70% 目标。
- Parquet、Excel、CSV 和多表业务继续使用已有格式约定，真人业务交付、跨模型及失败恢复验收仍需在隔离节点验证。现有生产版本保持原部署。发布需把 Java、Broker、Executor 和 Worker 按同一版本验收。若需回滚，按版本整体切回基线镜像，保留 Silo 数据和未终结任务的原契约。
