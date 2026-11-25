# Runtime OS 模型接入与验收

候选版本为 `1.2.0-rc.1`。整改以 `main` 的 `ec9d141b7` 为起点，已包含 Gateway 源分支 `b99c54b71`。整改分支同时保留了工作区已有的 Native 停止证明、有限重放及迟到回执核销修复。

## 源码审查和行为变化

| 链路 | 整改前 | 候选版本 |
| --- | --- | --- |
| 平台模型目录到 Native 任务合同 | 平台允许保存带斜杠、冒号等字符的名称，Native 捕获任务时仅接受窄范围别名格式，多项参数共用一个异常 | 保留原始逻辑名称，按任务 ID、指令和模型名称分别报错 |
| Worker broker 到 Gateway | broker 通用 fetch 和 Gateway 均可能自行结束长时间请求 | 明确配置的 Gateway Origin 使用仅限制连接建立的 broker 传输。专用客户端启用 Runtime Managed 后取消 Gateway 首包和流空闲超时，有限截止时间沿用 Runtime 时间戳 |
| Gateway 管理端诊断 | 与生产转发复用服务首包和空闲参数 | 诊断及在线测试独立使用诊断超时，普通请求继续使用各服务的超时 |
| SSE | 可配置在 EOF 时补写结束标记 | 转发上游真实结束标记，保护 finish_reason。错误事件、未完成 choice 和异常 EOF 返回单个模型调用错误 |
| 取消 | 下游断开会中止代理，但前端记录可能笼统标为取消 | Gateway 记录连接断开、明确的 Runtime 截止时间和上游失效。用户取消须依据 Runtime 的任务事件判断 |
| 并发和日志 | 全局值固定为 32，请求、响应和 SSE Event 使用固定字节限制 | 环境变量可配置限值。按请求固定配置版本，记录任务、会话、执行尝试及模型请求 ID，不把这些字段转发给供应商 |
| 单独启用统一 Native | Spring 同时发现 Native 适配器和持久任务服务，依赖 `CodingAgentSPI` 的控制器无法启动 | 单独启用统一 Native 时，用主路由按冻结合同选择执行适配器。已启用原 Cline 集成时，继续由原主路由处理 |

Java 的入口为 `NativeCodingTasks` 和 `NativeSiloTaskService`，执行恢复继续由 `NativeS3CodingAdapter` 和 Temporal 管理。Native Worker 按签名模型合同获取连接，Cline 使用 OpenAI Compatible 模式和本地 broker URL。模型转发及取消链路见 `cline-agent-server/src/governance-server.mjs` 和 `model-gateway-transport.mjs`。Gateway 仅支持该集成使用的 `POST /v1/chat/completions` 及 `GET /v1/models`，其他供应商协议在此候选中继续返回 501。实际 SDK 是否始终使用 Chat Completions，须以验收请求路径为准。Runtime 现有反代用于 Runtime API、回调或诊断，不应插入 broker 到 Gateway 的请求链路，验收使用 Gateway 直接发布的 HTTPS 端点。

## 接入配置

1. 在 Gateway 的 `.env` 为 Runtime OS 配置专用凭据，并限定可调用的 alias。普通客户端继续使用默认的 `ordinary`。

   ```dotenv
   GATEWAY_CLIENTS='[{"id":"runtime-os","key":"<运行环境自己的密钥>","models":["internal/qwen:tools"],"profile":"cline","mode":"runtime_managed"}]'
   ```

2. 在 Gateway 发布上游服务和模型映射。示例中的 `internal/qwen:tools` 是 Cline 调用 Gateway 时传入的 API 模型 ID，同时是 Gateway alias。真正的上游模型 ID 由映射指定，例如 `/models/Qwen3.6-35B-A3B`。
3. 在 Runtime 平台模型目录，将逻辑 `name` 设为稳定的目录名称，例如 `onprem-qwen`；将 `providerModel` 写成 Gateway alias `internal/qwen:tools`。Base URL 使用 `https://<证书域名>:8443/v1`，使用 OpenAI 兼容连接和该专用凭据。目录名称、Cline API 模型 ID 和供应商 ID 原样保存。
4. 在每个处理 Native 模型转发的治理节点设置 `RUNTIME_MODEL_GATEWAY_ORIGINS=https://<证书域名>:8443`，多个 Origin 用逗号分隔。该配置只接受完整 Origin，不含 `/v1`。如需私有 CA，在治理进程启动时通过 `NODE_EXTRA_CA_CERTS` 指向容器内已挂载的信任 PEM。Runtime OS 1.3.2 客户 Compose 可将该路径写到 `RUNTIME_MODEL_GATEWAY_CA_FILE`。Java 如需直接探测或调用同一 HTTPS 入口，按 [证书接入说明](https.md) 配置 Java 信任。

限值和诊断超时的变量见 [环境配置示例](../.env.example)。Compose 停止宽限时间应大于 Gateway 在途排空窗口。进程停止接收新请求后，在窗口内允许在途请求结束。窗口到期仍未结束的连接按网关关闭处理。

## 验证记录和未完成项

| 验证 | 证据位置或结果 |
| --- | --- |
| 慢首包、SSE 停顿、原始模型 ID、reasoning、tool_calls、usage、异常 EOF、并发槽和取消 | `tests/runtime-managed.test.ts`，8 项通过 |
| 配置更新、请求关联、普通与托管凭据隔离 | `tests/runtime-http.test.mjs`，真实 Gateway HTTP 进程通过。示例 Gateway 请求 ID 为 `4e437274-629c-43f0-9bd4-d72b59e9009d`，Runtime 请求 ID 为 `runtime-42`，配置版本为 2，HTTP 200 |
| broker 取消与错误回执 | `cline-agent-server/test/model-gateway-transport.test.mjs`，真实 HTTP 连接中止和 HTTP 200 内的失败 SSE 回执通过 |
| Runtime 模型名称和持久任务恢复 | `NativeSiloTaskServiceTest` 10 项通过，`NativeS3CodingAdapterTest` 30 项通过、1 项跳过，`AnalysisTaskWorkflowTest` 6 项通过。报告位于各 Maven 模块的 `target/surefire-reports` |
| 验证服务器源码编译与候选镜像 | 服务器已构建 Gateway、Runtime API 和 Native broker 候选镜像。Gateway 在服务器用 Node 24 编译，复用已验证的运行时依赖层，API 使用匹配的已有 Web 静态资源，Java 和 broker 使用整改源码 |
| 真实 Cline 4.1.27 + HTTPS Gateway，慢首包与流停顿 | 服务普通阈值为 100 ms，上游首包约 650 ms、SSE 停顿约 350 ms。真实 SDK 调用 Chat Completions 两轮，完成 `editor`，终态为 `turn_completed`。独立读取产物 SHA-256 为 `d0a8cb03641a2ab63c3f240b573b298d112e96b1b7543375968cfb0115d87c3b` |
| 真实 Cline 4.1.27 在途取消 | `tests/cline-managed-cancel.mjs` 通过。会话 `4c74ae03-ea19-490f-80c8-22b6a9d240ad` 进入 `cancelled`，Gateway 请求 `305626b4-667d-4bd4-a0ee-fd9a4d6ffd8e` 记录 499，模拟上游确认断开 |
| 真实 Runtime API 与 Native Worker 联合业务验收 | 隔离 Runtime API 登录、模型发布 `qa/native:gateway`、Silo 对象版本及 CSV 上传通过。分析提交返回 403，隔离环境缺少有效的 MCP Coding dispatch entitlement。Runtime 业务产物和任务恢复仍待联合验收 |

服务器证据位于 `/mnt/disk01/gateway-lifecycle-rc1/release-evidence/`。Gateway 隔离验收包位于同目录下的 `offline-candidate/`，镜像为 `llm-compatibility-gateway:1.2.0-rc.1-acceptance`，镜像包 SHA-256 为 `75640ab6f2348910cb6753a934cda47b56831763003765ef33cf4bf76e0c5f1ad`。该包用于隔离验收。完整生产发布提交和生产安装包须在联合验收通过后生成。

### 生产入口 18100 验证

2026-11-25（上海时区），在 `192.168.195.221:18100` 使用现网 Runtime API、授权和 Native Worker 提交合成 CSV。先走已发布的真实模型，再用临时、非默认模型把指定测试请求送到同机回环监听的候选 Gateway。候选 Gateway 连接受控模拟上游，普通模式首包阈值设为 100 ms。现网 Java、Native broker 和现网网关容器继续使用原镜像。候选 Gateway 的 HTTPS 验证见上一节的独立 SDK 用例。

| 请求 ID | 结果 | 可核对证据 |
| --- | --- | --- |
| `gateway-prod18100-018961e54f3f4661` | 真实模型 `qwen3-8-27b-gateway-validation` 完成 | 10 次 Runtime 回执与现网 Gateway 请求 ID 一一匹配，下载 `analysis.json` 的 SHA-256 为 `1b36e8f72394baec4ade3aca7fdd7e0ffa288e8c833a98bc79517a3a3a790c5b`，独立核对 3 行、合计 42 |
| `gateway-prod18100-516c8c4f92db4ba6` | 通过候选 Gateway 完成 | 两次请求均为 `runtime_managed`，首 Token 分别约 661 和 659 ms，下载产物 SHA-256 为 `a6f73562bab1a6c61b27ba456f35e1bfc5e26e737c0ca166a7e78c742f7802d4`，独立核对 3 行、合计 42 |
| `gateway-cancel18100-86f0544eda0644c0` | 上游正在等待首包时从 Runtime 发起取消，最终 `CANCELLED` | 执行器停止已确认，无待核实状态，Gateway 请求 `dd9d6cb6-274e-4e6c-9ea2-326e08fd444b` 记录 499，模拟上游确认断开 |

初次夹具假设 Native 会提供显式完成工具，造成上游 HTTP 400。Runtime 保留已执行工具记录，随后按测试任务 ID 停止并核对执行器已停止。复测夹具依据 SDK 提供的工具清单选用完成工具或普通结束文本。验证后已删除临时非默认模型并停止临时候选 Gateway 容器。脱敏证据包位于服务器 `/mnt/disk01/gateway-lifecycle-rc1/prod18100-evidence.tar.gz`。这轮验收覆盖现网 Runtime/Native 与候选 Gateway 的互操作，候选 Java 和 Native broker 的联合部署仍是独立发布门槛。

在隔离环境运行整链验收时，从真实 Runtime API 提交附件分析，通过可控模拟模型按请求回放以下场景。记录 Runtime runId、taskId、会话和执行尝试、Gateway 请求 ID、模拟上游请求序号及最终状态，并独立验证交付文件 SHA-256。

| 场景 | 需要保留的现场证据 |
| --- | --- |
| 首包慢、持续推理慢、SSE 停顿 | 间隔超过普通模式阈值，托管请求仍接收结束标记，任务继续 |
| 真实文件分析和两轮以上 Native Tool Calling | SDK 发出工具请求，文件读取、执行结果回传、真实分析产物和 Runtime 验收回执 |
| SSE 错误、异常 EOF、工具 JSON 未完成、连接中断 | Gateway 每次只发起一个上游请求，归档实际 SDK 错误与下一轮恢复，核对既有已完成工具和产物状态 |
| 用户在推理中停止、有限 Runtime 截止时间到期 | 任务终态、Worker 停止证明、Gateway 连接状态、上游实际断开和并发槽释放，区分两类原因 |
| 并发饱和与配置切换 | 超额请求获失败回执，运行中请求保留旧配置，随后请求使用新配置 |

`tests/cline-smoke.mjs` 可单独复核实际 SDK 与 Gateway 的 HTTPS 工具调用。使用 `TEST_RUNTIME_URL`、`TEST_RUNTIME_TOKEN` 和同一账号的已上传测试附件执行 `node tests/runtime-os-acceptance.mjs scenario.json evidence`。场景文件填写 `fileIds`、`modelName`、`instruction`、`expectedStatus`，可选 `artifactTypes` 和 `timeoutMs`。模型配置需绑定该场景的模拟上游。工具保存 Runtime 任务状态、监控回执、产物哈希及 Gateway 请求 ID。取消场景对保持在途的模型调用下发 Runtime 取消，Gateway 与模拟上游日志需按相关 ID 另行归档。仅将测试租户的脱敏证据纳入发布记录。

## 源码构建、离线包和回滚

在授权验证服务器从同一经核验的源码构建 API、Native worker 和 Gateway，并用隔离 Compose 项目运行 P0。验收通过后再定稿发布 SHA 和正式镜像。保留旧镜像 ID、Compose 配置、证书和 Gateway 数据卷备份。禁止在候选验收期间覆盖客户正在用的镜像标签。

正式 Gateway 构建和离线导出可以在 Linux 的干净发布检出目录运行下面的命令。验收阶段的未提交工作树使用单独的候选标签与 `GATEWAY_SOURCE_SHA=uncommitted`，验收记录另附基线和源码补丁哈希。构建节点需具备锁定依赖、基础镜像和 Debian 软件包的来源。

```sh
cd llm-compatibility-gateway
export GATEWAY_IMAGE=llm-compatibility-gateway:1.2.0-rc.1
export GATEWAY_SOURCE_SHA="$(git rev-parse HEAD)"
docker compose build llm-gateway
docker image inspect "$GATEWAY_IMAGE" --format '{{.Id}} {{json .Config.Labels}}'
docker image save --output llm-compatibility-gateway.tar "$GATEWAY_IMAGE"
sha256sum llm-compatibility-gateway.tar > llm-compatibility-gateway.tar.sha256
```

离线包同时提供 Compose、样例配置、证书导入和安装脚本。安装前校验 tar SHA-256，加载镜像后用已审核配置启动，记录构建提交、镜像 ID 及各组件版本。若验收失败，在同一部署目录恢复已备份的 Compose、客户端模式和旧镜像标签，使用 `docker compose up -d --no-build --pull never` 切回已验收版本。恢复配置或证书备份前先确认期间没有新的已批准变更。Runtime OS 状态库继续保留任务记录和证据，运行中的旧任务按 Runtime 停止和恢复流程处理。
