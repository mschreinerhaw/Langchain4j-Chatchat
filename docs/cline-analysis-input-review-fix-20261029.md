# Cline 原始消息解析与报告复核收尾修复

## 真实失败记录

本次直接读取生产任务事件、既有 Cline 监控及 Java 执行反馈；原始请求、程序和回执保留在服务器受保护目录 `/opt/chatchat-releases/cline-analysis-failures-20261029/private`。不在文档复制接口凭据、客户记录或上传文件内容。

| 请求 | 实际证据 | 断点 |
| --- | --- | --- |
| Excel 分析 `4b794d43-6451-4ea9-975c-f367410c5976` | Cline `analysis-coding-2e6c8ce0f6e78f4321addfd3de2aee237d9bedca3edbebfe4278ead54dc0ce3d`；前两轮输出缺失、第三轮 NameError，第四/第五轮已产生归档结果、引用有效，业务校验为 INCONCLUSIVE | 自动读取报告后，SDK 的下一模型请求隐藏了已有 artifact_read 完成工具。模型继续改写并提交无效提案，最终达到 12 轮上限；外层只显示 EXECUTION_FAILED |
| 接口响应分析 `bbd8cfa0-e5f6-46af-9119-a9bbda8f1f7d` | Cline `analysis-coding-1be6b5f8a550ba9cff5cac47cae5ea28440a854cefa2b251df81dc1fab735b4e`；4 次真实 Python 失败 | 首轮把原始 TEXT 当 analysis-materials JSON；后续把接口 JSON 连同后面的自然语言需求传给 json.loads，触发 Extra data。真实原文的 raw_decode 可识别完整 JSON 和后续 17 字符文本。通用 INPUT_FORMAT_ERROR 没有给出这一细分依据 |

INCONCLUSIVE 仅代表 Runtime 未作业务正确性判定，不能被伪造为业务验收通过。既有失败记录不修改为成功，新验收使用原任务治理入口产生独立重试身份。

## 最小改动

- 保留原 artifact_read 工具，允许 Cline 在审阅真实 reviewedArtifact 后显式确认完成。确认仅复用当前任务、最新成功执行的同一不可变报告；新执行（包括失败执行）清除缓存，不接受旧版本证据完成任务。继续允许有依据的修复与原有自然语言完成路径。
- Runtime 输入说明明确区分 nativeFileSchemas 中原始用户消息 TEXT、表格快照 JSON 单元和 manifest；混合 curl/JSON/需求文字需要识别 JSON 边界、保留尾部文本，而不是粗暴删转义、截断或硬编码数据。嵌入 curl 和凭据不授予联网权限。
- Python 捕获器增加固定安全分类 JSON_TRAILING_CONTENT，并保留白名单 JSONDecodeError 类型。Java 沿用原日志边界与执行反馈契约提供有针对性的 raw_decode 修复依据，不记录异常原文或业务值。
- 不增加模型轮次、Python 修复预算、任务总时限，不新增分析器、执行器或独立监控存储，不修改第三方 SDK。

## SQL Studio 先行发布

提交 `c9e70d232` 已推送；生产 API 镜像为 `runtime-os-api:sql-workbench-c9e70d232c30-20261029`。仅精确替换 API 及其共享网络代理，Node、MCP 和其他服务容器身份保持不变。两个配置库备份及原容器元数据保存在发布目录 `private/production`；无数据库迁移或数据恢复覆盖。生产公共入口 HTML 及正常鉴权 SQL 工程接口已验证。

## 验证进度

修复正在进行隔离联调与原始请求重试验证；最终计数、部署和残留限制以完成后的证据补充为准，不将自动化替身当作真实业务验收。

## 生产回放补充：同一文件的理解与分析

`f2d5bb92f` 部署后，接口原始请求重试 `18f57036-29ee-4cf5-a225-1b9a6ca124b4` 已 SUCCESS / SUCCEEDED，原问题完全一致，报告约 4,500 字。提供的响应实际为 7 条：3 SUCCESS、3 RUNNING_EXECUTION、1 STOP，与报告计数一致；报告未带出原 curl 凭据。

Excel 的首次原始重试 `f38c557a-8a49-4345-a5f6-37c572d9fd32` 被规划阶段以“多个工作流目标”阻止，实际没有进入 Cline。本次保留该 PARTIAL / NO_PRESENTABLE_RESULT 记录，未伪装成功。真实计划将理解同一上传文件结构和分析同一文件数据分为 DOCUMENT_UNDERSTANDING、DATA_ANALYSIS。

最小补齐：仅当 Runtime 已绑定原始文件、外部数据依赖为 NONE、恰有一个 DATA_ANALYSIS，且其他任务全部是 DOCUMENT_UNDERSTANDING 时，统一进入原 DATA_ANALYSIS/Cline 流程。所有任务目标和文件引用原样保留，不创建独立文档执行器。外部数据依赖、业务动作及无授权文件的组合仍保留原门禁。规划提示词同步明确文件结构理解是分析准备步骤；路由正确性不只依赖模型遵循提示词。

## 回放继续定位：未压缩对话的实际源码绑定

文件路由修复 `fe5a9db71` 后，原始重试 `020df5ee-e047-4053-8bd5-178294e85d35` 已进入 Cline，但最终仍 FAILED。只执行了一份 1,909 字符的诊断程序，未产出契约报告；之后提案的父版本/哈希正确，补丁锚点却不在该真实程序中，所有这些无效提案都没有执行。

读取原有私有 SDK 检查点后确认，`sdkPrepareTurn` 在 SDK 不需要压缩上下文时直接返回，因而不会提供它原本负责补充的最新源码。输出契约失败又没有异常行号，原局部源码反馈为空；模型便不断根据已被拒绝的提案猜测当前程序。

补齐该既有适配点：不论是否压缩，每轮都提供真实最新程序与版本；移除本适配器之前注入的旧绑定，保留 SDK 自己的压缩和完整工具调用/回执配对。补丁在 SDK 编辑前先按原精确锚点规则验证，编辑结果还必须等于该精确补丁结果；不放宽哈希或匹配规则。工具 Schema 同步声明原先已有的 32 个编辑、16,000 字符边界。新增短对话、被拒绝提案后的源码刷新及真实 SDK 错误修复闭环回归。
