# Cline Standalone 干净 Docker 补充验收

日期：2026-11-08。结论：**本轮最小 Standalone Server POC 完整真实任务验收通过**。判定基于全部编码任务、独立验证与控制场景结束后的结果，不以健康检查代替任务验收。范围仅补齐正式资源装配、干净镜像启动以及该镜像的真实 HTTP/SSE 复验，不改 Runtime OS 默认 Coding Agent。

关联：[本轮关键证据](evidence/cline-server-clean-docker-20261108/README.md)、[前轮报告](cline-server-runtime-tests.md)、[宿主差异矩阵](../audits/cline-server-host-diff.md)、[Docker 构建说明](../../cline-agent-server/docker/README.md)。

## 资源和镜像

固定官方 v4.1.26 提交 `a459f1d04dea030833b558be645011ab739c463b`。使用已验证的同 Tag Standalone 产品构建，未使用旧 SDK Worker、Java 执行逻辑或模拟 Agent 工具。Core/Agents 及四个既有宿主补丁均未增加修改。

官方 `apps/vscode/src/standalone/utils.ts:23` 从 cwd 读取 `proto/descriptor_set.pb`，:25 使用 proto-loader 解码。装配脚本现在拒绝非空目的目录、检查必需资产、复制完整官方生成 proto、生成逐文件 manifest；服务启动时核验全部文件及 descriptor 服务定义，worker cwd 固定为 runtime。不是运行后手工拷一个文件到容器。

最终镜像 `sha256:f23feae55353f57f2e7fb68455a41b2f26cd383b0ca04f928e0072ad7b869ace` 从 scratch 装配。中间工具镜像只提供 Node/明确列出的 OS 命令及动态库，最终不继承其应用/SDK 层。1850 个 runtime 文件完整性验证通过，descriptor SHA256 `d28dace3b4daff727770c3625917b22a0c54a8f855c4222530de1923b493086f`，解码 22 个服务；Node24.21.0、ABI137、SQLite3.50.4 加载通过。

最终容器仅挂 config、state、两个项目副本；没有 runtime/service/upstream source 挂载，没有旧 /app 和全局 SDK。只读 rootfs、UID1000、cap-drop ALL、no-new-privileges、3GiB、CPU quota2及cpuset6,7、PID256，仅宿主回环 `18327`。未声称生产租户隔离。

启动阶段已验证 API live/ready、真实 native worker.ready、Docker healthy；空 descriptor 挂载的负向测试按预期非零退出并精确指出 integrity mismatch。曾因 HEALTHCHECK 未带 token 访问受保护 ready 返回401，已修复为从私有配置读取端口/鉴权；失败记录保留。Docker Hub 无法连接，采用记录 Image ID 的本地工具环境离线装配，不声称已从公共仓库干净下载镜像。

## 完整真实任务

模型 `qwen3.7-plus`。两个全新工作副本取自 Runtime `7d076d0dc` 的完整 coding-agent-node archive，138 个 tracked 文件逐项与归档核对，两组起始内容相同，fixtures/skills 在任务前就存在。任务和前轮相同：monitor-client 参数边界、多斜杠、类型导出、README与新增测试。模型/工具都是真实调用。

Editor 主任务 session `bb5c639e-d097-4420-9295-d1414207bc7a`，task `5ceb2b0a-cc78-4124-b82c-eb14dd20e111`，native session `1794104562403_cj6k5`。396.3 秒、33 次审批后原生 turn_completed；独立构建、103/103 限定回归及参数检查通过，生成文件 Hash 前后一致。

Patch 主任务 session `1d19d003-354b-44fb-b19d-e72213d08881`，task `ed790a98-c1a5-4767-9592-40cf35c004d3`，native session `1794105019509_pkt8j`。230.7 秒、18 次审批后原生 turn_completed；独立构建、93/93 限定回归及参数检查通过，生成文件 Hash 前后一致。使用官方公开 toolRoutingRules 选择 apply_patch，没有修改算法或伪装模型名。

每组保留 HTTP 202/native session、原生工具输入输出、审批 ACK、SSE/journal、生成 Diff/文件 Hash、构建与测试退出码。独立验证器不修复模型输出，并检查生成文件 Hash 前后一致。参数行为测试中的 fetch fixture 只验证所生成客户端；Agent、模型与工具结果均为真实执行。

| 能力 | 本轮结果 | 原生回执位置 |
|---|---|---|
| Read | PASS，真实 read_files | editor/native-tool-results.json，eventId 49 |
| Search | PASS，后续 HTTP task `d186a414-b08a-48b9-97ff-eb0715806c43`，与 Editor 相同 native session | search/summary.json、native-tool-results.json；真实 search_codebase，未以 Shell 替代 |
| Edit | PASS，真实多文件 editor 写入 | editor/native-tool-results.json，首个成功 eventId 150；complete-project-changes.patch |
| Patch | PASS，真实 apply_patch 写入并自行修复失败测试 | patch/native-tool-results.json，首个成功 eventId 123；complete-project-changes.patch |
| Shell | PASS，模型自行构建、运行测试；保留退出码/结构化错误 | 两组 native-tool-results.json、events.json.gz 和独立构建日志 |
| 审批 | PASS，精确原生 resolver ACK 后执行；重复相同决策200、冲突409 | 两组 idempotency-and-approval-receipt.json；HTTP 200/200/409 对应原生 decision=1、applied ACK=1 |
| 任务幂等 | PASS，相同 key 两次202、异体409 | 两组同一持久化 key、native task.accepted 仅1；未重复投递 |
| 拒绝/晚到批准 | PASS，真实写入请求被 deny，marker 不存在，晚到 approve409 | controls/denial-events.json、http.json.gz、summary.json |
| 取消 | PASS，真实长 Shell 已运行后 HTTP cancel；确认 PID1688/1743/1744 停止 | controls/cancel-events.json；localStopConfirmed=true；额外35秒后延迟 marker 仍不存在 |
| SSE 断线重连 | PASS，Shell 运行时断开后仍 running，Last-Event-ID 35 后收到36 | controls/summary.json、http.json.gz；关闭连接不冒充取消 |

回归命令在模型结束后独立运行：`node scripts/build.mjs`，`node --test --test-concurrency=1 test/monitor-client-parameters.test.js test/monitor-authority.test.js test/node-monitor.test.js test/monitor.test.js`，以及仓库外独立参数检查。三个命令均 exit0；原始测试输入从任务前就完整存在，没有恢复输入或人工补代码。完整生成 Diff 包含模型创建的新测试和 CHANGES_SUMMARY.md，文件 Hash 单列。模型结束只记 turn_completed，API deliveryValidated=false，独立结果另记。

真实项目自身声明 SDK0.0.93 依赖，因此项目依赖目录仍包含它们。**它们未充当 Server 执行引擎或旧 Worker**：Server 只 fork 镜像 `/poc/runtime/cline-core.js`，cwd=runtime，不设置 NODE_PATH；该 bundle 与同 Tag 官方产品构建逐字节一致，608 个 Core/Agents 源文件仍零修改。engine-provenance.json 明确列出这一区别、实际派发调用点及全部原生 Shell 审批输入；没有启动项目服务替 Server 做编码。

## 保留的失败与定位

| 观察 | 原生/资源调用点 | 最终处理与限制 |
|---|---|---|
| descriptor 缺失/空资源 | `apps/vscode/src/standalone/utils.ts:23,25`，相对 runtime cwd 读取并解码 | 装配生成资源并 Hash 校验；空文件 Docker 负向测试非零，保持失败而非模拟服务 |
| 初次 Docker health401 | 本服务 Dockerfile HEALTHCHECK → server.mjs authorize → /health/ready | 从私有配置读取端口/token 后 healthy；失败日志单独留档 |
| Editor 9099字符被拒，随后不存在文件及非法 insert_line | Core `schemas.ts:10` / helpers 参数限制；`definitions.ts:761` 错误封装；官方 `sdk-diff-edit-coordinator.ts:403` / Core `executors/editor.ts:207` 行边界 | 原生循环自行拆分、创建文件和调整插入位置，错误回执未删除 |
| 首次新增测试失败 | 原生 `vscode-run-commands-tool.ts:567 → createShellExecutor` 返回 exit1 | 两组模型均通过原生 Editor/Patch 修复，随后重新构建/测试；独立验证通过 |
| npm/.bin 探测命令失败 | 干净镜像 OS/Node 命令清单及项目依赖布局；上述官方 Shell executor 正常返回非零 | 模型自主使用 node scripts/build.mjs；无 Java 补跑，无成功回执伪造 |
| 全仓 test/*.test.js 超时 | Core `definitions.ts:515` 的 bashTimeoutMs 默认30000；官方 background shell 调用 | 两组保留超时回执，限定回归通过；**全仓旧测试全集未通过完整验收**，不把管道 exit0 当全集成功 |
| IDE UI/诊断等专属 RPC | HostBridge 对应 window/diff/workspace 未实现方法 | 仍显式 UNIMPLEMENTED；本次用官方 background edit/exec，不扩大宿主能力范围 |

源码行号与原始 Hash 见 source-assets-location.json。native-tool-results.json 同时保留原始 transport 类型和按结构化 success/error 得到的结果；发生过的工具失败不回写为成功。原生输出本身若截断，保持原样，完整请求可在对应审批 input 中核对。

## 最终状态与边界

最终记录时间 `2026-11-08T02:35:34Z`：live/ready200、Docker healthy、runtime 全部 Hash 仍一致、oom_kill=0，容器仅 API 主进程存活。源码、模型使用量、文件 Hash/Diff、构建/测试日志、控制回执和68个导出文件的 manifest 一并留存；认证密钥与 reasoning 不导出，价格 UNKNOWN。

运行实例：`/opt/cline-agent-server-clean-20261108`，容器 `cline-agent-server-clean-poc`，宿主 `127.0.0.1:18327`。既有服务和 Runtime OS 默认 Coding Agent 未改变。完整 VS Code Desktop 等价、租户隔离、故障恢复与外部 MCP/Sandbox 操作停止仍未验收；本次拒绝/取消只证明本地执行范围，不扩大本轮通过结论。
