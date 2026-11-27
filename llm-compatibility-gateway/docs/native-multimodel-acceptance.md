# Native 多模型兼容性审查与现场验收

基线为 Runtime `5813e5201`、Gateway `1.2.0-rc.1`、Cline `v4.1.27`（`8075d2e35cf5cad67c24f180c80a05d311b74a3e`）。整改代码为 `7681c4c05`。候选镜像与客户现场配置需独立验收。

## 源码审查

| 链路 | 现有实现 | 本次整改或待验证事项 |
| --- | --- | --- |
| Java Runtime → Native | `NativeSiloTaskService` 冻结模型配置，按任务 ID、指令和逻辑名称各自校验。Native 在服务端解析冻结的模型配置 | 复用区分逻辑名称、Gateway alias 和真实模型 ID 的配置。现场只使用已确认的上游 ID |
| Native → 模型 | `worker-executor.mjs` 将冻结的模型 ID 传给 Cline OpenAI Compatible Provider。该 Provider 请求 SSE 和 Usage。Broker 只按模型配置应用输出限值和思考参数 | Cline v4.1.27 的 Harness、工具循环和恢复逻辑保持原样。上下文和输出限制从实际服务配置采集 |
| Gateway 请求规则 | alias 精确匹配，模型级规则先运行，客户端 profile 规则随后运行。配置版本、审计和回滚已有实现 | 体检可选择同样的 profile，显示历史检测版本，保留早期 HTTP 拒绝的最小元数据。规则仍保护 `stream`、`tools`、`tool_choice` 及 `usage` |
| SSE | Gateway 转发真实事件并核对结束标记。Runtime Managed 由 Runtime 控制截止时间 | 增加基础 SSE、可选参数和 Usage 的逐项对照。Broker 按完整 SSE 事件读取计量，多行、分片、重复帧和断流都有覆盖 |
| Token | Broker 记录已观察的上游 Usage，Runtime 按请求对应的 Native operation ID 汇总，监控展示回执。缓存与推理 Token 用独立字段 | 展示已确认总量，区分完整、部分和未知。Gateway 统计只用于诊断。严格额度验收比对 Runtime 合计与唯一请求 ID 的真实回执 |
| 模型能力 | Gateway 已有流式、工具、推理和视觉配置，兼容性检测保存版本与时间 | 增加并行工具和流式 Usage 两项可选能力。缺省显示未验证。现场验收通过后再配置生产可用范围 |

详细诊断沿用管理员会话。请求实验室只应重放脱敏样本，临时追踪会展示适配前后内容和脱敏后的上游错误。检测存档只保留摘要，生产请求日志不存正文、鉴权头及推理内容。

需要查看真实 Native 请求时，在 Gateway「请求日志」中选择模型和客户端，点击「捕获下一条」，然后从系统发起一次任务。两分钟内匹配的第一条已鉴权的 Chat Completions 请求会显示在「收到的 Cline 请求」和「转发给模型」中，同时显示参数差异、可用的关联 ID 和 HTTP 结果。同一管理员会话可查看五分钟内的结果或主动清除。捕获只保存在进程内存，消息正文、图片、工具实参、认证信息及未识别的文本值会被遮蔽。

## 模型接入及用途

Runtime 的 `providerModel` 使用 Gateway alias，Gateway 的 `upstream` 按推理服务 `/v1/models` 或启动配置确认。Runtime Base URL 使用 Gateway 的 `/v1`，Gateway 则按其上游服务配置合并 API 路径。新增模型沿用 [Runtime 接入配置](runtime-os-integration.md)，每个模型使用自己的版本、能力和规则记录。

| 模型 | 建议 Gateway alias | 客户现场证据 |
| --- | --- | --- |
| Qwen3.8-27B | `qwen/qwen3.8-27b` | 默认分析候选。需通过现场数据、工具和严格计量验收 |
| Qwen3.6-35B-A3B | `qwen/qwen3.6-35b-a3b` | 高并发候选。需实测数据分析、峰值并发和尾延迟 |
| DeepSeek-V4-Flash-0731-INT8 | `deepseek/deepseek-v4-flash-0731-int8` | 复杂推理候选。现场终端的 `DeepSeek-V4-Flash-0731` 非流式请求成功。INT8 权重标识、Native 请求到达的入口和历史推理回传需分别核对 |
| Qwen3-VL-8B-Instruct | `qwen/qwen3-vl-8b-instruct` | 视觉专用候选。使用权限隔离的报表截图，校验内容识别与图片大小限制 |
| Qwen3-32B | `qwen/qwen3-32b` | 备用候选。现场映射 `/models/Qwen3-32B` 的普通请求成功。此前 Native 流式请求收到上游 400。拒绝字段未知 |

## 复现 Qwen3-32B HTTP 400

记录 Gateway 配置版本、client profile、推理镜像、权重及 tokenizer/chat template 版本。选择同一个模型和 profile 运行「兼容性检测」，按下面顺序比对 HTTP 状态及上游原始错误。诊断使用独立超时。拿到首个失败后，在「请求实验室」用脱敏合成数据做最小重放。

| 编号 | 变化和证据 |
| --- | --- |
| T01、T02 | 保持同一提示，分别发普通请求和基础 SSE。保存上游状态及 Content-Type |
| T03 | 在基础 SSE 增加 `stream_options.include_usage`。同时核对尾部 Usage 帧和结束状态 |
| T04、T05、T06 | 对相同流式工具提示分别增加 `tools`、`tool_choice: "auto"`、`parallel_tool_calls`。保存拒绝字段、工具参数分片和工具 ID |
| T07 | 在基础 SSE 分别单测 `max_tokens` 和 `max_completion_tokens` |
| T08 | 在实验室按实际服务协议分别加入一个思考参数。检查推理与正文是否分离 |
| T09 | 使用同一 Gateway alias 和 profile 重放真实 Native 请求的脱敏等价结构，核对转发 URL、模型 ID、字段差异和上游原始错误 |
| T10 | 用实际 Cline 执行工具，核对 `tool_call_id`、工具结果回传和后续模型请求。DeepSeek 启用思考时额外核对历史 `reasoning_content` |

上游确认某个可选字段不兼容后，才为对应模型或 profile 配置规则。若拒绝的是流式 Usage 请求，先验证适配后的流中或厂商公开的受控接口仍能提供本次请求的真实 Usage。缺失时标记未知，该模型不得作为严格 Token 额度场景的通过项。工具解析器配置问题由推理服务修复。

## 真实 Native 验收

使用只有本次测试权限的账户上传合成 CSV。可使用 `region,amount,quantity` 三列，三行数据为 `east,10,2`、`east,12,3`、`west,20,4`，独立预期为 3 行、0 个空值、east 金额 22、west 金额 20、总金额 42、总数量 9。要求报告与机器可核对的 JSON 同时交付。多数据集测试使用第二份授权维度表，视觉测试使用具有已知答案的脱敏图片。

复用 `tests/runtime-os-acceptance.mjs`。场景 JSON 提供 `fileIds`、`modelName`、`instruction`、`expectedStatus` 和要验证的 `expectedResult`。需要严格计量时设置 `requireCompleteUsage: true`，并按用例设 `expectedMinToolCalls`。报告记录 Runtime 任务 ID、Gateway 请求 ID、任务状态、真实 Token 回执及产物 SHA-256。

```bash
node tests/runtime-os-acceptance.mjs scenario.json evidence
```

环境变量 `TEST_RUNTIME_URL` 与 `TEST_RUNTIME_TOKEN` 由执行环境提供。对故障场景记录 400、401/403、413、429、5xx、超时、SSE 断流、工具异常、Python 异常、Gateway 重启、Worker 恢复、取消和迟到回执。成功样本再测单任务、并发、长上下文和多轮工具。采集 TTFT、P50/P95/P99、成功率、模型调用数、真实用量与资源占用。

## 发布与回滚

在隔离项目导入候选镜像后复用现场 Gateway 配置副本、CA 和与其匹配的 Native Broker。用测试账户和测试数据验收。改动前记录镜像 ID、配置版本和数据库备份。回滚配置用 Gateway 的「配置与审计」回滚到指定版本。镜像回滚重新选择此前验证的标签并只重建对应组件。检测记录和配置数据库保持旧版可读。生产默认模型需在完成真实 Native、严格计量和系统回归之后由管理员审批。

## 当前证据范围

客户网关及 Runtime 入口从开发工作区和验证服务器访问均超时，现场 400 根因和五个模型的生产可用性待实测。模拟上游可以稳定复现基础 SSE 成功、加入 `stream_options.include_usage` 后 HTTP 400，证明诊断能够隔离该字段。这份模拟证据不能确定现场 400 的根因。并发性能、视觉和客户多数据集任务未验收。

| 验证 | 结果与证据 |
| --- | --- |
| Gateway 协议和生命周期 | 22 个测试通过。额外的真实 HTTP 进程测试复现 400、核对早期 401 日志、验证模型级规则隔离以及配置回滚。夹具的替代响应仍携带真实 Usage 帧 |
| Native Broker | 11 个测试通过，覆盖取消、HTTP 200 SSE 错误、分片、多行 SSE、重复和中断 Usage 帧。通过已读取的完整回执计量。缺失或部分数据按未知或部分展示 |
| Java 重点回归 | 56 个通过，1 个跳过。覆盖 Native 恢复、任务创建、Temporal、监控和模型回执重放顺序 |
| Maven Reactor 回归 | 在 `chatchat-agents` 停止。1891 项中 91 个失败、38 个错误、2 个跳过。与 `5813e5201` 基线复跑的 129 个失败或错误用例逐一相同。后续模块的全量结果待补齐 |
| 候选 Cline 4.1.27 → HTTPS Gateway → 受控上游 | 会话 `18a14b62-420e-423b-a151-11873c5e5aca` 到达 `turn_completed`，实际执行 `editor` 并完成工具结果回传。Gateway 请求为 `d5d3d2d0-c22c-4b57-9512-2fbca6afd4b0` 和 `685a1093-70db-4884-89bb-9064d0643a0c`。独立读取工作区产物，SHA-256 为 `d0a8cb03641a2ab63c3f240b573b298d112e96b1b7543375968cfb0115d87c3b` |

候选镜像在 `192.168.195.221` 生成。Gateway 在服务器离线编译，Native Worker 更新基线上已编译的 Node 服务脚本，API 使用本地 Maven 编译结果更新既有基线 JAR 中的对应类和监控资源。Gateway 已隔离启动并通过 `/ready`，Native Worker 已通过隔离 Cline 测试，API 已完成 JAR 分层和完整性检查。三者的客户现场联合启动仍待验收。

| 文件 | SHA-256 |
| --- | --- |
| `native-multimodel-candidate-7681c4c05-images.tar.gz`（Gateway、Native Worker） | `c3ee4af7b73b5e58b1d9078f484f59dc0b84ad20281e9af9dee122bb9673dd21` |
| `native-multimodel-candidate-7681c4c05-api.tar.gz`（API） | `d5f30aa73c6cec1a613bd8e30433c216f28eda1e16ea965436a83f12f286635f` |

镜像及校验文件位于服务器 `/mnt/disk01/gateway-lifecycle-rc1/native-multimodel-candidate/dist/`。校验后可使用 `docker load -i <归档>` 导入隔离环境。给 Gateway、Native Worker、API 显式指定各自的 `native-multimodel-7681c4c05` 候选标签，Gateway 标签前缀为 `1.2.0-rc.1-`。上线审批前使用独立配置和测试账户。

后续生产门槛包括五模型的真实数据或图片产物、性能测试，以及按任务能力和计量证据执行的生产准入联动。当前模型检测只提供能力证据与配置，模型选择继续使用现有授权和启用状态。
