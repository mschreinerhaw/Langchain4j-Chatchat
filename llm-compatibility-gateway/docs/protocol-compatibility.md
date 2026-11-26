# Gateway 协议适配与隔离验收

基于首轮 Native 多模型整改和请求捕获提交 `60343c6ed`。Cline 固定为 v4.1.27。Runtime OS 负责授权、预算和任务生命周期；Cline 负责工具选择、执行和多轮编排；Gateway 负责模型转发和协议转换。

## 审查与整改范围

| 链路 | 已有实现 | 本轮增加 |
| --- | --- | --- |
| 入口与上游 | 精确 alias、正确合并路径、自定义认证头、配置快照、版本与回滚 | 管理页允许直接输入内网自定义 alias。服务可提供协议默认值，单个模型可覆盖 |
| 请求规则 | 模型规则后应用客户端 profile 规则，保护 `model`、`stream`、`messages`、`tools` 和 `tool_choice` | 明确的旧版 Functions 协议转换、可选图片 URL 结构转换。已有可选参数规则继续负责 Token 限制、思考字段和扩展参数 |
| 响应与 SSE | 有界 UTF-8 与 SSE 解析、真实增量转发、取消与异常处理 | 显式终止事件、推理字段或起始 `<think>` 标签转换、真实 Usage 字段映射、兼容模式下的重复用量帧识别 |
| 工具 | 标准 tool_calls 透传 | 旧版工具调度消息双向适配，同一请求内关联历史工具 ID。旧版返回的工具名和完整 JSON 参数经过当前工具集合及 Schema 验证 |
| 诊断 | 实时请求捕获、请求实验室、逐项体检、配置版本和回滚 | 体检复用同一适配流水线，提供离线命令行验收摘要。请求捕获保留转换前后的结构 |

协议适配默认透传，按**配置**启用。客户端 profile 的请求规则仍在传输转换前应用。配置发布后，在途请求沿用原快照。状态、认证、模型 ID、工具和正文的保护边界不因模型名称而改变。

## 配置

在单个模型或上游服务的「协议适配配置」填 JSON。服务级设置用于同一上游共享协议，模型级设置覆盖对应字段。省略配置即可使用默认行为。模型级可用 `"tools":"openai"`、`"reasoning":"openai"`、`"images":"openai"` 或将 `usage`、`usageEvent`、`doneEvent` 设为 `null` 关闭对应的服务默认适配。模型提供 `usage` 时替换整个字段映射。

下面是**受控旧版服务的示例**。先用体检和请求捕获验证客户上游的实际差异，再决定需要配置的字段。

```json
{
  "tools": "legacy-functions",
  "reasoning": "think-tags",
  "usageEvent": "metering",
  "doneEvent": "finished",
  "usage": {
    "prompt_tokens": "meter.input",
    "completion_tokens": "meter.output",
    "total_tokens": "meter.total",
    "cached_tokens": "meter.cache",
    "reasoning_tokens": "meter.reasoning"
  }
}
```

- `legacy-functions` 将单工具调用映射到旧版 `functions` 和 `function_call`。上游的名称与 arguments 分片按 choice 汇集到 64 KiB 上限。收到真实 `function_call` 终态且验证参数后才向 Cline 发出标准调用。返回的合成 ID 只标识上游确实提出的调用。Cline 回传标准 ID 后，网关用同一请求内的 assistant 历史匹配旧版 `function` 角色。旧版协议无法保真表达显式并行调用、同一 assistant 轮多个调用和部分非文本工具结果，这些请求返回明确错误。普通自然语言和未定义工具标记继续按文本处理。
- `reasoning-field` 和 `thinking-field` 将明确的独立字符串字段移到 `reasoning_content`。`think-tags` 只识别正文起始的 `<think>…</think>`，支持跨 SSE 分片并拒绝未闭合的推理块。常规正文中后续出现的标签仍保留为文本。Gateway 会保留 Cline 实际回传的历史推理字段。需要历史字段但客户端未提供的模型仍需现场判断可用范围，当前实现不跨任务缓存推理内容。
- `flat-image-url` 用于明确接受 `{"type":"image_url","image_url":"…"}` 的上游，保留 URL 或 Base64 数据本身。图片的其他控制字段无法保真转换时拒绝请求，不下载或改写图片。默认使用标准 OpenAI 图片对象。
- `usage` 只映射非负安全整数。已存在的标准字段优先，缓存和推理量按标准 details 字段呈现。按实际数据记录 `COMPLETE`、`PARTIAL` 或 `UNKNOWN`；完整表示已取得真实总量或真实输入和输出，缺失量不赋零。Gateway 请求日志用于诊断，Runtime 按已有关联 ID 汇总扣量。
- `doneEvent` 需要真实上游命名事件，并且已有完成的 choice。Gateway 才会转换该显式信号。未知断流、提前结束、SSE 错误或不完整工具调用仍会失败。默认继续要求真正的 `[DONE]`。

## 五模型模板和现场验证

[五模型停用模板](templates/onprem-models.disabled.json) 包含建议 alias 和待填的上游地址与模型 ID。模板适用于空白隔离 Gateway。现有环境先导出配置，将所需条目合并到副本，再通过现有配置审计页面验证和发布。上游 ID 用现场现有服务已接受的原值，认证使用已授权凭据。模板不包含启用的候选规则。

| 模型 | 先验证的协议 |
| --- | --- |
| Qwen3.8-27B | 标准 SSE、原生工具、推理与正文分离、真实流式 Usage |
| Qwen3.6-35B-A3B | 标准 SSE、工具结果回传、推理参数、并发时的尾延迟 |
| Qwen3-VL-8B-Instruct | 标准 `image_url` 和混合消息。现场证明需要扁平结构后再选择对应适配 |
| Qwen3-32B | 单变量复现 400，再根据上游原始错误决定参数规则或工具协议 |
| DeepSeek-V4-Flash-0731-INT8 | 现场量化版本真实 ID、思考模式的多轮回传、真实 Usage，核对缺失历史字段时的能力边界 |

使用 Gateway 「兼容性检测」选择相同 alias 和客户端 profile，依次比较非流式、基础 SSE、`include_usage`、工具定义、工具选择、并行工具、限制和思考参数。失败时从「请求日志」捕获一次同模型的真实请求，结合「请求实验室」的脱敏上游原始错误比较。保留确切 HTTP 状态及首个会触发失败的单变量。现阶段已知 Qwen3-32B 现场非流式成功，Native 流式曾收到 400，具体拒绝字段待现场确认。

已有 Gateway 管理员和 HTTPS 信任可用时，离线运行以下脚本。由执行环境注入 `TEST_GATEWAY_ADMIN`、`TEST_GATEWAY_ADMIN_PASSWORD`。私有 CA 使用 `NODE_EXTRA_CA_CERTS`。脚本不会改上游服务配置，报告只保存探测摘要和配置版本。退出码 2 表示有失败探测。

```bash
node tests/gateway-protocol-acceptance.mjs qwen/qwen3-32b qwen32-check.json cline
```

部署隔离镜像、真实 Native 任务、独立核对产物以及严格用量验收见 [Native 多模型验收说明](native-multimodel-acceptance.md)。视觉识别和工具执行必须核对实际任务结果。单一协议通过不会自动启用模型或标记生产可用。

## 迁移与回滚

适配字段为可选字段，旧 SQLite 配置无需迁移。发布前保存配置 revision、证书、配置数据库及原镜像标识。先在独立 Gateway 使用配置副本和受控测试凭据校验。回滚规则使用「配置与审计」中的历史版本。回滚镜像时同时恢复曾通过旧版读写验证的配置 revision，避免旧版再次发布时遗漏新增字段。
