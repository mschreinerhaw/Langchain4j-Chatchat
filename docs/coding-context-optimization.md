# Coding 上下文优化实施与配置

## 本批实现

1. **真实请求归因**：继续记录system/tools/task/skills/code/report/history字节与 UTF8/4估算；增加上一请求相同内容的重传量、本轮完全相同part的重复量、新part量。Tracker仅保存4096个哈希，不保存第二套对话。固定system和tools属于必要重传，不能把全部重传量称作浪费或实际可节约Token。
2. **原生压缩状态**：使用已安装SDK 0.0.93的 `createCompactionStateAwarePrepareTurn`，保存SDK prefix-hash状态与计数到已有私有checkpoint。规范消息仍由Agent拥有；不做业务摘要、不增加LLM调用。规范前缀不一致时SDK弃用旧投影；工具调用未闭合时不压缩。
3. **源码重复投递**：仅在本任务已授权且实际advertise `code_read`、存在当前版本hash时，受保护绑定省略完整source，保留版本与hash并指向已有读取工具。原始工具调用中的source与完整证据不删除，显式code_read返回完整当前源码，精确补丁及陈旧hash校验保持。没有code_read的项目/模式回退完整源码。
4. **类型往返**：SDK tool_result返回portable表示时恢复原JSON值，避免Java再包一层引号/反斜线。被basic压缩成非JSON文本的内容仍保留文本，不伪造原始完整结果。
5. **分阶段时间**：新增Node→Java回调的connect、headers、body、整体HTTP时间；与Java provider调用时间、本地queue和供应商真实TTFT分列。非流式供应商没有TTFT则保留null，不能由回调首事件推算。连接复用没有新connect测量时为null。
6. **错误与监控**：区分连接错误、回调截止、主动取消、HTTP拒绝、传输错误、非法JSON。非法模型JSON不能退化为空成功结果。Java有界事件白名单与现有Node只读监控保留新数值、缓存用量与reasoning用量；高级指标折叠显示，原始prompt和源码不进入新增遥测。

## 配置和回退

Node可信部署环境变量 `RUNTIME_CODING_CONTEXT_OPTIMIZATION_ENABLED=true` 同时启用原生状态复用与源码引用绑定。默认 **false**；观测能力不依赖启用这个开关。合同和模型输出不能自行打开部署策略。

关闭开关并重启受控Worker即可恢复完整源码绑定和无状态basic投影；SDK规范消息、执行回执、代码版本和授权不变。部署前须检查活跃任务并保留镜像回滚，不能强杀生产任务。模型绑定、输出上限、模型超时、网络策略与Python Sandbox均未调整。

SDK实际自动阈值仍为0.9；本批没有伪造一个0.65参数或把累计预算当物理窗口。后续若要提前压缩，应以已验证窗口和实际provider用量试验受支持的manual/native预算策略，再独立验收。

## 任务工作记忆设计

采用SDK原生规范消息＋原生压缩状态＋已有代码生命周期的组合，不另存完整历史：

| Memory内容 | 所有者与恢复来源 |
|---|---|
| Goal / Constraints | 原始任务合同与SDK首条规范消息；合同hash、scope保持Java验证 |
| Current Stage / Completed Steps | Node工具生命周期事件、已配对的SDK工具回执 |
| Active Plan / Pending Actions | Cline实际生成的消息；缺显式计划时不由Java猜测 |
| Code References | 既有codeLifecycle版本、当前源码hash和有效版本 |
| Verified Evidence | 已有输入/结果/validation/evidence引用；摘要不能授权额外数据 |
| Failed Attempts | SDK工具错误与代码版本状态；未知执行结果必须先对账 |
| 压缩的历史工作集 | SDK prefix-hash sidecar，保存在已有私有checkpoint |

本批没有引入额外agentic业务阶段摘要。其原生summarizer需要接入相同Java预算和取消链，未经该验证不默认启用。SDK basic是技术压缩，不能冒称已经实现业务语义记忆。

## 受保护材料与后续边界

- 完整当前报告仍保留供Cline逐项语义复核；尚未改为章节/引用投递，避免把不完整报告当完整报告审核。
- Skills目录发现和skill_read按需加载已有实现，继续复用，不新增Java关键词选择。
- 旧checkpoint不含压缩状态可以直接恢复；新sidecar是可选字段。附加投影导致超限时，只省略可重算sidecar，规范消息、代码和回执不删除；完整checkpoint继续受Java的原有大小上限约束，本批未提高上限。
- 代表性长分析、图表报告、真实Sandbox、Worker进程重启后的完整任务成功率、阶段摘要、供应商streaming/内部队列观测仍需后续受控验收；不能用本批小型代码生成对照代替。

## 可复现命令

`cd coding-agent-node && npm.cmd test`；`npm.cmd run test:browser`；`node scripts/benchmark-context-projection.mjs`。

Java定向：`mvn.cmd -pl chatchat-api -am test -Dtest=ClineRuntimeModelBridgeTest,ClineModelCallbackControllerTest,ClineModelReceiptStoreTest,ClineModelReceiptOrderingTest -Dsurefire.failIfNoSpecifiedTests=false -Dfrontend.skip=true -Dfrontend.install.skip=true`。

真实模型隔离集成复用 `CodingWorkspaceNodeIntegrationTest#authorizedRealModelComparison`，设置 `coding.nodeE2e=true`、`coding.sqlglotPython`、`coding.realModelConfig`、`coding.allowedModel`、`coding.benchmarkOutput`，分别设置 `coding.contextOptimizationEnabled=false/true`。凭据文件必须在Git之外；本次通过临时loopback+SSH转发使用服务器现有模型，真实供应商凭据未离开服务器。转发开销两组相同，但不等同生产网络环境。
