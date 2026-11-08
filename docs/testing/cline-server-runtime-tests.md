# Cline Agent Server POC 真实验收报告

日期：2026-11-08。结论：**固定 v4.1.26 Standalone 产品链的最小 HTTP Server 编码 POC 通过**。真实模型经 HTTP 驱动原生 Agent 完成真实项目的多文件修改、构建与测试，另完成原生搜索、拒绝审批及运行中取消。此结论不代表完整 VS Code 宿主等价或生产上线验收。

源码：[服务与复现说明](../../cline-agent-server/README.md)；[宿主差异矩阵](../audits/cline-server-host-diff.md)；[下一阶段整改](../design/cline-server-poc-remediation-20261108.md)。证据统一位于 [本次证据目录](evidence/cline-server-poc-20261108/README.md)。未改 Java 执行逻辑，未切换现有生产 Coding Agent、业务 API 或 MCP 服务。

## 固定输入与真实执行链

- 官方 `v4.1.26`：`a459f1d04dea030833b558be645011ab739c463b`；已核验正式 VSIX SHA256 `d8ea4c3505052c48e7c7a5558e7213a53827b6ba1da7e56513c87d896df51c4d`。本次 Linux Server 从同 Tag 源码构建，不宣称与 VSIX 字节相同。
- 真实模型 `qwen3.7-plus`，使用现有平台授权的 OpenAI-compatible 模型发布。平台和 MCP 管理端均完成真实登录及认证读取；只导出状态码，不导出令牌或密钥。MCP 登录成功不等于原生 MCP 工具验收。
- 真实项目：当前 Runtime 仓库 `7d076d0dc` 的 `coding-agent-node`，独立工作副本。任务是修复 monitor-client 的 URL、分页参数安全整数边界、无效输入提前拒绝、公开类型与 README，并新增回归测试。完整冻结任务见 [task.txt](../../cline-agent-server/test/task.txt)，三组主任务 prompt SHA256 相同：`b2fa1b5e8bd0cf11068965d768311f0882ac81078c74c31c7da41e71cdeafc93`。
- 官方 `build:sdk → compile-standalone` 主体 → `package-standalone.mjs -s`，Bun 1.4.2、Node 24.21，同 ABI 编译/运行 native sqlite。运行目录补入官方生成的 `proto/descriptor_set.pb`，没有拿旧 SDK 包替代 Core。
- HTTP → 官方 Standalone TaskService/SdkController → VscodeSessionHost → 同版本 Core/Agents → 原生 Editor/Patch/Shell。HTTP 层只负责会话、审批关联、事件与进程管理。Core/Agents **608 个源文件逐字节对照，零修改**；四个宿主文件的差异见 [原始 patch](evidence/cline-server-poc-20261108/native-host-diff.patch)。

## 同任务结果

| 组 | 真实任务结果 | 原生工具与独立验证 | 新增测试 / 扩展回归 |
|---|---|---|---|
| 未修改官方 Standalone 参考 | `awaiting_followup`，真实 completion_result | 官方默认 Editor；独立参数验证 PASS | 57/57；90/90 |
| Server 默认 Editor | `turn_completed`，339.89 秒，25 次审批 | `read_files`、`editor`、`run_commands`；独立参数验证 PASS | 46/46；79/79 |
| Server 原生 Patch 专项 | `turn_completed`，244.16 秒，21 次审批 | `read_files`、`apply_patch`、`run_commands`；独立参数验证 PASS | 33/33；66/66 |

Editor session：`7f24d8d4-24f7-4d08-8a30-f7a1f6ad0fc5`，task：`0b8474af-7f03-4628-a731-8b5006bf10ff`。Patch session：`090cd4c1-a9a1-4327-9960-6e74a151da69`，task：`fbff465d-27c5-487a-a581-1b0ef10be0d9`。逐次工具输入、原生结构化结果、HTTP 回执、生成 diff 和文件 Hash 均留档。

三组均由模型自行读取和修改实现、类型、README 与新增测试，并执行真实构建及新增测试。Editor 曾遇到官方 6000 字符参数限制及测试失败，原生循环自行拆分、修复后通过；没有验收程序代写代码。工具返回的结构化 `success:false` 被判为失败，不把 transport content_end 当成功。

默认 Qwen 路由没有触发 apply_patch，因此 Patch 是明确的配置专项：通过 Core 公开 `toolRoutingRules` 启用官方 apply_patch、禁用 editor；模型名称、Patch 解析与应用算法均未改。搜索也是明确的补充用例：在 Editor 的**同一个 native session**发送后续 HTTP 请求，真实 `search_codebase` 与 `read_files` 均触发，task `3dfe1aaa-099c-42c9-a488-364580ab4591`；不能说原主任务自动触发了搜索。

参考组使用未修改的官方 Standalone bundle 和同一个 Server HostBridge，通过官方 StateService 观察完成状态，不是 VS Code Desktop 组；参考组为隔离副本自动审批，Server 为逐工具 HTTP 审批。因此本次证明同任务核心编码能力，**不构成完整 VSIX A/B 等价或性能优劣结论**。参考源码/产物 Hash 见 stock/source-verification.json。

扩展回归为独立追加验证：初始 scratch Git 暂存漏了原仓库的 fixtures/skills，后来只恢复原始测试输入，生成的实现、README、新测试与 dist 文件 Hash 前后相同。恢复账本与直接 exitCode=0 的回归日志分别保存。执行四个测试文件：`monitor-client-parameters`、`monitor-authority`、`node-monitor`、`monitor`，`--test-concurrency=1`。这不是全仓测试通过声明，亦不冒充模型在原回合完成的命令。

## HTTP 与控制验收

| 场景 | 结果与实证 |
|---|---|
| 审批 | 原生 toolCallId、输入 Hash、epoch 绑定 resolver；approve 后实际执行；同决策重试幂等、冲突拒绝 |
| 拒绝 | 真实模型申请写 marker 的 Shell，经 HTTP deny；实际 marker 不存在；晚到批准被拒绝 |
| 运行中取消 | 先批准真实长运行 Node Shell，确认子进程启动，再 HTTP cancel；native cancel 后停止本地 worker/进程树；两个被跟踪 PID 均停，延迟 marker 未生成 |
| SSE 断线 | 真实 Shell 运行时主动断开 SSE，任务仍 running；重连回放 ID 34 后收到 35，断线不冒充取消 |
| 消息幂等 | 相同 Idempotency-Key 重试不重复投递；异体同 key 409；单活跃 worker |
| API 边界 | 缺 token 401、未知工作区 400、未知会话 404、`__proto__` 工作区 400 |
| 取消后可用 | 新建 native worker ready，取消清理后 API 仍 200；最终容器仅 API 进程存活 |

控制用例未配置外部 MCP/Sandbox 操作，停止证明仅覆盖本地进程；没有外部副作用停止/对账结论。native 成功结束只记 `turn_completed`，`deliveryValidated=false`，独立验证另记。模型价格无法可信核验，usage 保留但费用为 UNKNOWN；原生零价格不能当真实账单。

## 失败与未通过项定位

| 问题 | 定位与处理 | 剩余边界 |
|---|---|---|
| R1 验收驱动错误 | Python Request.method 修为 get_method；审批脚本误把测试 heredoc 内 `../dist` 当越界；API 把可恢复原生 error 当终态也已修正 | R1 失败证据保留，未改写为 PASS |
| R2 worker 被杀 | cgroup v1 下仅 CPU quota，Node 看见 24 核；默认全测试并发触发 3 GiB OOM，kernel/cgroup 证明 | 加 cpuset 后 Node=2；最终新容器 oom_kill=0。R2 端到端仍 FAIL，不能用其独立验证通过掩盖 |
| Editor 输入过长 | `sdk/packages/core/src/extensions/tools/schemas.ts:10` 定义 INPUT_ARG_CHAR_LIMIT=6000，`helpers.ts:26,30` 校验 | 原生错误反馈后模型拆分成功；算法未改 |
| 长 Shell 默认超时 | `sdk/packages/core/src/extensions/tools/definitions.ts:515` 使用 `bashTimeoutMs ?? 30000`；vscode-run-commands-tool 的 background shell 包装未传专项超时 | 全仓旧测试未获完整通过；下一阶段增加明确宿主超时 profile，保持官方 Shell executor |
| 缺测试输入 | scratch Git 暂存遗漏 fixtures/skills，非 Agent 缺失；prepare-real-project.sh 现校验完整归档 | 已只恢复原输入并独立回归；不得人工修复生成实现 |
| Standalone 分发缺 proto | HostBridge/ProtoBus 需要 descriptor_set.pb；官方分发排除资产未覆盖本宿主加载路径 | assemble-runtime.py 显式复制生成 proto，并保留 Hash |
| IDE 专属能力 | ExternalEditPreview、host diff/window/diagnostics RPC，见 HostBridge 对应 UNIMPLEMENTED 分支 | headless 任务走官方 background edit/exec；未实现 IDE UI/诊断等价 |
| 生产治理 | server.mjs 单操作者配置、单活跃 worker；凭据/文件挂载共容器；崩溃后只标 recoverable | 租户隔离、网关预算、外部取消、恢复与 OpenAPI 未验收，详见整改建议 |

保留失败与成功版本各自 adapter Hash。Shell 管道可能隐藏前序失败，不能只凭工具 exit0 判测试成功；本报告结果结合测试汇总、直接 exitCode 与独立验证。没有新增 Java Agent loop、Editor、Patch 或 Shell 执行器。

## 留存实例

独立实例：`/opt/cline-agent-server-poc-20261108`，容器 `cline-agent-server-poc`，仅宿主 `127.0.0.1:18326`，需 SSH 转发访问。私有授权配置在服务器留存供继续验证，未进入仓库/镜像/证据。生产现有服务保持原状。
