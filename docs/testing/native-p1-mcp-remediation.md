# P1 MCP 实施与验收记录

当前状态：**适配代码已实现，真实产品验收未通过；最终 P1 未通过。** 不以组件测试替代端到端验收。

本阶段基于 [范围冻结矩阵](../audits/native-p1-remediation-matrix.md)，P0 基线 `d5dd48356` 保持独立。SDK 固定 0.0.93；无模型、预算、SDK Loop、Editor/Patch 或金融业务改动。

## 源码断点与最小修复

`NativeCodingTaskService.Request/submit` 增加 MCP 服务/Agent 授权范围绑定；`NativeCodingMcpService.catalog` 从既有 `McpServiceDirectory` 读取真实服务目录，使用 `ResourceAuthorizationPort` 过滤，不替模型挑选本题工具。目录冻结在原合同上下文中，包含服务、远端名、已发布本地名、原始 Schema 与 Hash。`CodingRuntimeContext` 校验并递归冻结目录，`CodingTaskContract` 限制为原生任务使用。

`NativeCodingWorkspaceService.open` 返回冻结服务器身份。`server.httpGateway` 和 `ClineModelCallbackController` 补发现/调用回调；只传原模型参数与 toolCallId，不序列化 SDK Context、Signal 或任意客户端身份。Java 核对实际模型回执及原准入参数，逐次检查当前权限、服务状态和 Schema。旧固定集合之外的工具只通过冻结目录扩展；`ClineCodingAdapter`、`ClineRuntimeModelBridge` 和 `DurableCodingTaskService` 复用该判断。旧 Worker 必须先协商 `runtime_os.native_mcp.v1` 才能接受新合同。

Node 继续调用 SDK `createMcpTools`，通过其公开 `nameTransform` 复用已发布目录名，保留原 Schema、参数和返回值。多服务准备使用 SDK 同等的 `allSettled` 隔离语义；仅暂时不可用 HTTP 502/503/504 可降级，授权/完整性失败仍拒绝。每轮通过 SDK `beforeModel` 重新投影当前可见工具，撤权隐藏后续披露；每次执行还有独立再授权。新增目录/Schema 在新任务生效，不热替换旧执行器。

连接、重连与协议传输仍全部属于 Java 现有目录/网关。没有在 Node 增加另一套 MCP 客户端。

## 恢复与限制

MCP 分派前在既有任务 journal 保存 `DISPATCHING`，返回后保存原结果。确认结果可重放；分派后丢失响应不能自动重发。`NativeCodingWorkspaceService.finishTool/recoverTools` 不会因 Worker 停止就假称远端 MCP 操作停止。传输异常或仍可重试的未确认结果保持待协调；通用 MCP 没有可核对结果时，系统拒绝虚报成功。

这项保护本身不等于所有 MCP 中断可自动完成交付。需要真实连接中断、同任务恢复和停止/归档证据确认。当前未宣称覆盖不提供操作状态查询的外部副作用服务自动恢复。

## 组件验证与环境问题

本机使用独立安装的 Node 22.18.0，SDK 包和项目锁文件未更新。初次 Node 22.14.0 不能加载 TypeScript；该次失败不能作为产品回归。Java 首轮暴露缺少目录合同字段；其他失败包括新测试合同构造不完整、能力端点断言尚未包含新字段。各轮原始日志保留，最终以最后完整执行记录为准。

Windows CRLF 检出改变已固定 Hash 的 `native_command*.mjs` 资源字节；恢复为 Git blob 的原 LF 字节并增加 `.gitattributes` 检出规则。未更改原 helper 实现或其期望 Hash。

组件命令：`npm --prefix coding-agent-node test`；Java 使用 `mvn -pl chatchat-api -am -Pbackend-only -Dfrontend.skip=true -Dfrontend.install.skip=true -Dtest=NativeCodingMcpServiceTest,NativeCodingWorkspaceServiceTest,ClineCodingAdapterDispatchTest,ClineCodingAdapterRecoveryTest,ClineRuntimeModelBridgeTest,ClineModelCallbackControllerTest,NativeCodeWorkspaceTest -Dsurefire.failIfNoSpecifiedTests=false test`。

测试覆盖：冻结目录与 Schema、重复身份、跨租户/撤权、真实模型回执参数准入、响应丢失不重派、原结果重放、SDK 工具披露/反馈、每轮撤权可见性、多服务隔离与拒绝安全降级，以及原派发/恢复/回调回归。这里的模型与 Host 为组件夹具，不标为真实 MCP 或模型验收。

最终组件结果：Node **322 通过、1 跳过、0 失败**；Java **91 通过、0 失败**。[机器清单及七组 Java 结果](evidence/native-p1/mcp/components/manifest.json)保留 17 份原始失败/通过日志的 gzip、原字节 Hash 和压缩文件 Hash。PowerShell UTF-16 日志仅在提取汇总时解码，归档保留原字节。

## 部署与回滚边界

`scripts/package-native-p1-overlay.mjs` 只打包列明的 common/agents/API class families 与 Worker dist，附源码/文件 Hash。`scripts/deploy-native-p1-mcp-stage.py` 只允许原 P0 隔离 API/Worker 镜像，逐条校验非目标 Jar 内容不变；保留旧容器为 `*-before-p1-mcp`。MCP、Sandbox、模型及预算配置不改。脚本构建成功不代表产品通过。

回滚前停止新准入，核对任务与所有已启动操作独立停止、Worker activeSessions=0，再停止 P1 Worker/API，恢复保留的旧 API/Worker；Worker 通过原私有配置在 stdin 接收 secret。不要删除历史任务、Silo 资产或原失败日志。代码回滚撤回本专项提交，P0 分支无需修改。
