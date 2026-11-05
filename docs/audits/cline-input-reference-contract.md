# Coding 输入引用合同

当前 Worker `0449eba07`，Java `c97962110`。引用整改继承 f09cb4b5d；计数恢复修复未改变数据读取合同。

| 字段 | 权威来源与语义 | 使用方式 |
|---|---|---|
| inputSnapshotRef | Java 冻结的任务整体输入绑定 | 保持合同绑定，不能作 input_read.datasetReference |
| datasetReference | inputVersions 或授权工具返回的 calculationInput | input_read 的精确数据集参数；Java 仍验证是否实际交付 |
| sourceDatasetRef | manifest/nativeFiles 声明的原始数据来源 | 按原描述与 source 定义关联，不能自行猜测为另一个引用 |
| fileId | Java 已交付的不可变文件 ID | 在已授权 Python 挂载中读取 |
| virtualPath | files/fileLayout 声明的真实 Sandbox 路径 | 直接按描述使用，不能拼接臆造路径或当数据集引用 |

引用类型按已声明字段确定，不按字符串前缀、长度或正则猜测；不自动替换错误参数，也不披露其它数据集。
input_read 的 JSON Schema 现在在 datasetReference 属性明确说明来源、错误类型及文件字段区别，仍为原工具、原回调。

精确收到当前 contract.inputSnapshotRef 时，返回现有 SDK 工具结果中的结构化失败：

```json
{"status":"FAILED","error":{"code":"INVALID_REFERENCE_TYPE","expectedType":"DATASET_REFERENCE","receivedType":"INPUT_SNAPSHOT_REFERENCE","recoverable":true,"nextAction":"Use an authorized datasetReference from the delivered input descriptors."}}
```

afterTool 将该结果按 SDK 原机制标为错误；TOOL_FINISHED 记录对应 diagnosticCode。
模型可在相同任务/会话更正参数。没有读取数据，没有把 snapshot 自动映射成任何数据集。
未知或未授权数据集仍送 Java 原有数据授权检查；不能因 Node 的错误提示成为合法读取。
此改动不新增 Java 异常协议，直接调用 Java 的非法/未知引用仍遵循原拒绝路径。

input_read 继续使用 Unicode code-point offset 和最大16000字符的有界 limit，不是文件字节偏移。
对于 Runtime 已投递的 schema、hash、完整文件位置和有界观察，不新增强制重读；业务语义不足时仍允许自主探索。
自动化在同一实际 SDK 会话故意传错 snapshot，再传合法 dataset，验证 Java 模拟网关仅收到一次正确读取，
错误阶段不执行 Python、不读取其它数据；这是受控测试，不冒充真实生产错误注入。

## 真实模型观测

Worker 第二候选 CSV 样本 analysis-coding-2c3248474e6ac20400b79ebb8560f821d82e13c99c492ad0e8f87584915003c7
在首次模型响应中同时发起正确输入读取和错误快照读取。错误调用在 Node 返回 INVALID_REFERENCE_TYPE，
没有转为 Java 数据读取；同一 SDK 会话随后读取必要方法并在49,176ms首次提出代码。
后续报告合同修复及若干代码提案拒绝均保留，整项任务398,678ms后 COMPLETED/resultAvailable=true。
模型没有重新读取已经成功获得的输入。这是实际错误反馈后继续工作的证据；
“只先传错、再更正参数”的精确顺序仍由受控 SDK 回归覆盖，不将两种时序混淆。
见 [实际脱敏事件](../testing/evidence/cline-precode-20261106/reference-real-timeline.json)。
