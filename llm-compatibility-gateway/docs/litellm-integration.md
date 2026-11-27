# LiteLLM 协议适配与离线部署

Gateway 的 `FRAMEWORK` 模式将 Cline 的 OpenAI Chat Completions 请求交给 LiteLLM 的供应商适配器。LiteLLM 处理上游 API 路径、认证、消息格式、工具参数及工具结果回传。Cline 继续执行工具和推理循环。Runtime OS 继续管理任务状态、取消和预算。

## 选型与配置

LiteLLM 提供可独立运行的 OpenAI Compatible 入口及供应商协议适配器，适合接在现有 Gateway 后端。Vercel AI SDK 的 Provider 接口适合由 TypeScript 应用主动调用，接入这条链路仍需实现 HTTP 协议桥接。Bifrost 也有供应商适配器，本轮依照任务书先验证 LiteLLM。现有的 SSE 分帧、HTTP 传输和 Schema 校验继续用于 Gateway 自身职责。
参考 [LiteLLM 供应商适配](https://docs.litellm.ai/docs/providers/)、[Vercel AI SDK Provider](https://ai-sdk.dev/providers/ai-sdk-providers/openai-compatible-providers) 和 [Bifrost 文档](https://docs.getbifrost.ai/)。

| 模式 | 模型映射 | 用量 |
| --- | --- | --- |
| DIRECT | alias → 厂商实际模型 ID | 保留供应商返回的用量 |
| FRAMEWORK | alias → LiteLLM model_name → 厂商实际模型 ID | 当前为 UNKNOWN |
| CUSTOM | alias → 厂商实际模型 ID，以及模型级协议规则 | 保留有来源的用量，继续使用现有规则 |

未填写模式的存量映射按是否配置兼容规则保持原有行为。FRAMEWORK 模式支持模型或客户端配置的请求及请求头规则。供应商地址和凭据由 LiteLLM 配置持有。Gateway 拒绝客户端传入的地址、凭据、重试和降级控制字段。示例配置关闭重试、降级、缓存及遥测。网关请求超时仍采用已有的 Runtime Managed 策略。

LiteLLM 的流组装逻辑可以填充上游缺失的 Token 用量，当前 Chat Completions 载荷无法逐请求证明该值来自供应商。Gateway 在 FRAMEWORK 模式过滤缺少来源证明的 usage，并保留 UNKNOWN，避免将估算记入企业账本。要求真实用量的模型路径应继续使用已核验的 DIRECT 或 CUSTOM。参考 [固定版本流组装源码](https://github.com/BerriAI/litellm/blob/v1.110.3/litellm/litellm_core_utils/streaming_handler.py)。

## 离线部署

候选架构为 linux/amd64。先保存现有 Gateway 配置版本和当前镜像标识。在隔离目标执行 `sha256sum -c SHA256SUMS`，再执行 `docker load -i images-amd64.tar.gz`。旧版 Docker 导入后如无法按仓库摘要解析镜像，先核对归档校验和及导入镜像 ID，再在部署环境中指定归档内的标签。

使用 [Compose 文件](../deploy/litellm/compose.yml)、[上游路由模板](../deploy/litellm/config.example.yaml) 和 [镜像版本](../deploy/litellm/images.env)。将 LiteLLM 加入 Gateway 专用网络，保持 4000 端口只在该网络内可达。私有环境文件提供 `LITELLM_MASTER_KEY`、`UPSTREAM_API_BASE`、`UPSTREAM_API_KEY`。如果 Docker 注入企业代理，将内网推理主机加入 `NO_PROXY` 和 `no_proxy`。

```sh
export LITELLM_GATEWAY_NETWORK=<Gateway专用网络>
export LITELLM_ENV_FILE=/已授权目录/litellm.private.env
docker compose --env-file deploy/litellm/images.env -f deploy/litellm/compose.yml up -d
```

在现有管理页添加上游服务，框架选择 LiteLLM，地址填 `http://litellm:4000`，认证使用私有 Bearer 密钥。添加模型映射，模式选 FRAMEWORK，实际模型 ID 填 LiteLLM 配置里的 `model_name`。Runtime OS 和 Cline 保持使用 Gateway 地址及原有模型别名。配置完整发布后，以新的测试任务验证。回滚时先在 Gateway 恢复原配置版本和镜像，再停用 LiteLLM 服务。

## 候选镜像和依赖检查

- Gateway 候选标签为 `llm-compatibility-gateway:1.2.0-rc.1-litellm-1.110.3`。
- Gateway 镜像 ID 为 `sha256:de01e1ebc3521a2c9eae5789cd2153b74c39ca689309bb411da2d998984e6230`。
- LiteLLM 固定为 `v1.110.3`，OCI 摘要见上方镜像版本文件。核心供应商适配采用 [MIT 授权](https://www.litellm.ai/about)。
- 离线镜像归档 `images-amd64.tar.gz` 的 SHA-256 为 `e2e97af7a42091911afe8f247187dc5b9e77a203d9c50572da88bddee32d98df`。
- Gateway 生产 npm 依赖审计返回 0 条公告。对 LiteLLM 镜像中的 205 个 Python 包版本执行 OSV 批量查询，返回 0 个命中。镜像系统包扫描和签名验证待补。官方历史 PyPI 供应链公告见 [GitHub 安全公告](https://github.com/advisories/GHSA-92x9-889m-jgmw)。

## 公网模型边界

目前用已配置的阿里云 MaaS OpenAI Compatible 接口实测 `qwen3.8-27b`。量化方式和账户计价未从端点确认。现有 MaaS 配置中另有 `qwen3.6-35b-a3b` 可供后续测试。Qwen3-32B、Qwen3-VL-8B-Instruct 和 DeepSeek-V4-Flash-0731-INT8 的精确部署版本及能力仍待单独验收。内网 Qwen3-32B 的 HTTP 400 原因待客户端点按同请求取证。

## 2026-11-26 验收记录

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| OpenAI Chat Completions → LiteLLM → Anthropic Messages 模拟厂商 | PROTOCOL_PASS | 模拟厂商实际收到两次 `POST /v1/messages`，验证 system、Schema、工具参数、工具 ID 和下一轮 `tool_result`，Gateway 请求 ID 为 `63fba2fd-3bd0-485f-b8c3-c5993b17e452`、`a5822f80-c178-42e3-9fcc-12abc77747dd` |
| 真实 Cline v4.1.27 + 公网 Qwen3.8-27B，DIRECT | PUBLIC_MODEL_PASS，合成数据分析 | Session `104c141a-3dcd-449d-a1b2-da1fc501fdef`，报告对应的最终 Gateway 请求 ID 为 `a00f44e9-8846-4f97-895b-a5ef14b8b8f4` |
| 真实 Cline v4.1.27 + 相同 Qwen，FRAMEWORK | PUBLIC_MODEL_PASS，合成数据分析 | Session `d7084850-5241-49cc-8f26-f7a7701fbc22`，最终请求 ID 为 `20895c2d-1a3a-4119-a4fa-f00ae8c32a96` |
| FRAMEWORK 用量来源 | PARTIAL_PASS | Gateway 五次流请求均为 UNKNOWN。Cline SDK 在缺少用量事件时仍会发出零值原生用量事件，零值不能作为计费证据。正式启用按量额度前需要供应商原始用量和 Runtime 账本联合验收 |
| 独立推理模型、视觉、Java Runtime OS 实际任务恢复和取消、客户端点 | NOT_TESTED | 本轮证据覆盖 Cline SDK 和 Gateway。现有 Java、Cline 代码继续使用原版本 |

合成 CSV 有四条记录，其中一条金额为空。两条链路都使用 Cline 原生 `read_files → editor → run_commands → read_files` 生成及执行 Python，并回读报告。FRAMEWORK 的一次命令超出测试授权白名单，被拒后通过仅授权的 Python 命令继续完成。报告为 `rows=4`、`East=25`、`West=7`、`total=32`、`missing=1`，独立读取核对后两个文件 SHA-256 均为 `3e0c48c0be4ddc6d3b681b58db06df0d82d3c593cca6db478c0b05943775668ce`。

| 同一输入、模型端点和 SDK | DIRECT | FRAMEWORK |
| --- | --- | --- |
| Gateway 流式成功请求 | 5 | 5 |
| 首输出中位数 | 1024 ms | 456 ms |
| 请求总耗时中位数 | 3806 ms | 2965 ms |
| Gateway 确认总 Token | 19,692 | 未知 |

以上时延来自各一次公网任务，包含上游推理和网络时间。额外 CPU、内存和负载开销需独立压测，LiteLLM 镜像解包大小约 1.36 GB。协议样本见 [模拟厂商服务](../tests/litellm-provider-fixture.mjs) 和 [自动化测试](../tests/compat.test.ts)。Gateway 回归共 28 项通过，HTTP 运行测试另有 1 项通过。

## 2026-11-27 的 18100 验证

模型协议验收允许供应商 Usage 缺失。有可靠回执时使用回执，Cline 自身的估算可作展示和运行参考。企业额度和账本验收仍使用可核对的供应商用量，缺失保持未知。

- 在生产 `18100` 注册了非默认的临时模型，走真实 Runtime OS、Cline、Gateway、LiteLLM 和公网 Qwen3.8-27B。请求 `litellm-live-bizcjvz9` 完成，记录 9 次模型请求。归档 `analysis.json` 经独立检查为 3 行、合计 42，SHA-256 为 `1b36e8f72394bbaec4ade3aca7fdd7e0ffa288e8c833a98bc79517a3a3a790c5b`。验证后移除临时模型，复核默认模型配置。
- 隔离 HTTP 断连测试 `041a5302-b2a6-4e9e-becf-16c5977ccde9` 在约 70 ms 内关闭上游流。合成上游返回 429 后只收到一次请求。
- 从生产 Runtime API 对慢流任务 `litellm-live-h37sdat4` 发起取消，约 2.77 秒确认任务为 `CANCELLED`。模拟上游确认流提前断开，临时模型已移除。
- 在生产 Runtime 和 Cline 联合验收中，对 `litellm-live-4wp8fj8_` 的首次流请求注入 HTTP 503（Gateway 请求 `b853863d-95bd-457d-aa21-f44676251182`）。后续请求成功，任务为 `COMPLETED`，归档结果仍为 3 行、合计 42。临时模型已移除。

生产网关和默认模型继续使用原配置。503 恢复覆盖单次模型请求故障，Worker 重启后的任务恢复和发布镜像签名及系统依赖复核仍待验收。

## 模型错误文件和参考用量

直连、CUSTOM 和 FRAMEWORK 的模型响应错误均写入 Gateway 数据卷的 `model-errors.ndjson`（默认 `/app/data/model-errors.ndjson`）。使用 `gatewayRequestId`、上游请求 ID、模型别名、Runtime 任务标识和 HTTP/SSE 错误码关联排查。日志只取结构化错误码、字段名和脱敏后的首行消息。文件权限为 0600，约 8 MiB 轮转，保留一个旧文件。读不到原始错误体时仍记录响应状态。

FRAMEWORK 返回的 Usage 在 Gateway 指标中标记 `FRAMEWORK_UNVERIFIED`，供排查使用。普通调用可收到该值，Runtime Managed 链路保留现有记账边界，由 Cline 决定是否展示自身估算。缺少 Usage 不影响模型调用。
