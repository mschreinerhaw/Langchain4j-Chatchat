# 通用 Coding Agent 代码级根因矩阵

基准 `e9eda1b9a`，独立分支 `feature/runtime-native-coding-agent-20261107`。原生 SDK 固定 0.0.93，对应官方提交 `1a5c3f84e0933268a9dba14a55a6383a6578414d`。本矩阵针对通用编码架构；历史六次金融报告失败不作为原生工具缺失的因果证明，不重复修改 `5ba60b2ac` 已解决的旧资源授权。

## 现状与最小整改

| 代码级证据 | 根因及界限 | 最小整改 | 独立验证 |
| --- | --- | --- | --- |
| `coding-agent-node/src/runner.js`、`code-repair.js`、`sdk-adapter.js` | 旧路径已有多文件源码协议及部分 SDK 编辑能力；不能称为完全没有编辑工具。主要交互仍围绕代码提交、版本参数和 Python 执行，缺少独立通用项目入口 | 保留旧路径；增加独立 `native-runner.js`，只运行一个原生 Agent | native-runner tests；旧 runner 全量回归 |
| `completion-context.js`、旧工具装配 | 原生工具名与当前业务工具名不同本身不能证明能力损失；需要比较实际 executor、Schema、反馈及授权 | 原生 read/search/editor/patch 定义与 executor，共享同一项目视图；官方模型路由按实际 provider model 解析 | 原生多文件读、搜、改、Patch 测试 |
| SDK `createEditorExecutor`、`computePatchChanges` | `restrictToCwd` 不足以阻止绝对路径；Patch 计算阶段也可能读取路径 | 所有路径先验证，staging 后原生算法，提交成功才发布；不编写第二套编辑算法 | 越界、绝对路径、注入、软硬链接、失败第二 hunk 不部分提交 |
| `python_execute` 与旧代码版本协议 | 编辑、代码提交、版本创建与执行耦合；不适合作为通用项目唯一交互方式 | 独立 workspace commit 与 run_commands；每次执行绑定不可变树，原兼容路径不删除 | 连续修改而不执行、执行失败后继续编辑、版本回执不匹配拒绝 |
| Node 缓存与远端 durable commit | 提交成功但 ACK 丢失时，旧本地目录可能过期；缓存不是持久事实 | Silo 作为权威，丢失 ACK 后重读；缓存丢失重建，不重复编辑 | lost-ACK、stale manifest、missing cache tests |
| Java 原始模型回执与 callback | 仅校验工具名字不能证明执行参数得到模型授权 | 校验真实 model/toolCallId/参数等价性；A2 工具 start intent 与预算先于 I/O | 参数替换、未准入调用、重复操作拒绝 |
| `NativeCodingController` HTTP 实测 400；构建未保留反射参数名 | 新增路由使用未命名的 `@PathVariable`，请求在进入服务和模型前失败；属于 Runtime Adapter 缺陷 | 显式指定 requestId/taskId/version 名称，不改变编译器全局设置 | 同一 HTTP 测试修复前 10 error，修复后 10 pass，包含身份及操作绑定 |
| 第一次真实模型预检：读取及编辑成功，8 次调用后失败，实际 CODE=0；API JAR 不含 helper，MCP JAR 含 helper | `NativeCodingCommandService.helperHash()` 依赖 MCP 的 Python 资源，但 MCP server 只是 API 的 test-scope 依赖，单测掩盖运行包缺失；通用 bound 又把内部异常显示为 callback binding 错误 | 将唯一 helper 移至已有 common 运行资源，API/MCP 共享同一字节；启用组件时即校验 helper，避免模型用完预算才发现缺包 | 去掉 MCP server 依赖的子 JVM 读取测试；真实 A2 命令准入到 dispatch 测试；包内容检查和后续真实模型复测 |
| `NativeCodingCommandService` 与 Sandbox helper | Worker 提供的命令字符串不能单独作为授权事实；SDK 支持 argv/heredoc 归一化 | 冻结原输入 Hash；同版 SDK 在 Sandbox 独立归一化，核对 ordinal/命令 Hash；宿主不运行命令 | 原始输入不匹配拒绝、缺结果记录拒绝；实际 Docker 执行待整链验收 |
| 队列命令与工具停止并发 | 工具关闭与后续子命令启动之间存在竞争风险 | 同任务锁排序 start/stop/commit，已关闭工具不能再派发命令 | queued command/start-after-stop tests |
| A2 `reconcileStopped` 把任意剩余 start intent 视为 recovery；原生一次 run_commands 可以含多个 Sandbox 子执行 | 子执行已经独立停止，但其同一会话内仍在运行的父工具被标为未知，下一子命令不能准入。真实 A2 两项测试中正常父工具场景失败，存在其他未知模型的拒绝场景通过 | 仅当 native 子执行、已验证停止、同 fence/真实模型绑定且唯一剩余 start 为其活跃父工具时，允许父工具继续；所有未停止记录及终态约束保留，其他未知执行仍阻断 | 两个独立分支：合法同工具下一命令；存在额外未知模型必须拒绝。不得全局跳过 reconciliation |
| SDK Restore 与 A2 journal | SDK 恢复不等于恢复原累计预算；Worker 崩溃不等于执行器已停止 | Java 保持预算与 fence；分别核对 MODEL/TOOL/CODE 回执、真实停止证明，再继续同一会话 | recovery tests；真实 Worker kill/restart 尚待完成 |
| 官方 `agents/src/agent-runtime.ts:2336` 先 prepare 全批工具，再执行；prepare 中调用 beforeTool。真实第二轮 model-1 同时返回 read_files/run_commands，后者在 CODE 启动前被拒绝 EXECUTION_OUTCOME_UNKNOWN | Node beforeTool 过早 durable start：读工具完成时，未执行的下一工具看起来仍未停止，触发 A2 reconciliation 标志 | 原生 SDK 不改；在实际 tool.execute 前准入并记录 start；beforeTool 只校验不可变授权名单 | 安装版原生 Agent 的同轮读/执行独立复现，修复前 FAILED；同一原生 Loop 复测 |
| `sdk-adapter.js` 在预算完成阶段压缩，并把保护的最新 tool round 重新放到尾部；真实第二轮已有三个测试通过，最后三轮均无工具 | 最新完成反馈未作为保护内容，能被压缩删除；确定性测试实际看到 guard=-1，模型缺少必须继续验证的反馈 | 通用 native profile 保护最新工具轮及其后的反馈，保持因果顺序；旧业务默认行为保留，仍由原生 SDK 压缩更早上下文 | 安装版 SDK compactor + Agent 复现：修复前缺失验证反馈；不得增加 Prompt 或预算掩盖 |
| 原生命令 exitCode=0 | 可能没有命令结果文件，或没有真正满足项目测试 | 必须有匹配代码版本、结果、独立 stop proof；最终交付仍标 INCONCLUSIVE，独立验收另做 | 零退出缺结果、过期代码成功、缺 stop proof 均拒绝 |
| 第三次真实模型预检已多次执行三个通过的测试；命令为 `cd /workspace/code && python -m unittest ...`，但完成判断只比较无前缀命令的字节 Hash | 固定 Sandbox cwd 下明确等价的目录前缀被当作未验证；并且最后任意检查命令会覆盖此前有效验证，违反成功后继续检查的需求 | 服务端从冻结的声明命令生成有限的固定 cwd 等价 Hash，只接收原样命令及固定目录前缀，保留原始命令/回执；单独保留当前版本的已验证执行，源码变化即失效；不接受管道、额外重定向或失败掩盖后缀 | 原生 Loop：等价目录前缀及后续检查应完成，零退出管道必须拒绝；Java 分类负向；实际同预算复测 |
| 最终归档与提交 ACK | 已归档但回复丢失可能导致重试覆盖最终结论 | 固定 final intent，终态重试返回已存结果，不再执行 | persistence / final intent tests |
| `native-skills.js` 与 SDK compaction | 技术摘要和原 Skill 文件不是同一访问对象；恢复后不能绕过撤权 | 复用已有发布管理与 SDK discovery/loader，工具读请求逐次治理 | 撤权、篡改、压缩后再加载、跨进程 rediscovery tests |

## Worker 恢复专项：源码先行发现（待故障注入验证）

对象形态修复后，第二批 B 的原始回执显示两次真实单元测试均通过，但命令仅追加 `2>&1`。该合并 stderr 的 shell 操作保留原退出状态，不能与 `| tail` 或 `|| true` 混为一谈。旧负向测试曾把所有重定向一概拒绝，这个完成门槛过严。最小更正仅增加字面尾缀 ` 2>&1` 的声明命令身份，仍核对独立退出码、当前源码和停止证明；不得由测试输出文本推断成功，也不允许管道和掩盖失败后缀。该更正改变的是治理等价性，不要求模型新增提示词约束。

`server.httpGateway` 的截止时间例外只包含旧 Python 的取消与结算，遗漏 `native-command-cancel` 和 `native-tool-finish`。`native-runner` 的 abort 已触发取消，但过期合同在发出取消 HTTP 前被拒绝；已中止的信号也可能阻止真实工具停止回执。新增 HTTP 测试修复前在取消回调之前失败。最小修复须仅允许有界的停止/结算通道继续，保留 Java 对 CODE 独立 stop proof 的核验；过期任务的新执行和推理仍拒绝。此处是 Coding Agent 的截止与停止链路，不修改上层业务 Agent 超时。

通用批次 B/C 暴露了固定 cwd 修复的遗漏：SDK 原生 `run_commands` 同时接受字符串和 `{command: string}`。真实模型选择对象形态，Sandbox 回执正确保存的是对象 canonical Hash；完成门槛却只认可字符串 Hash。B 的三次测试都通过，独立复核也通过，但 8 次模型调用后仍失败。不能据此归因于模型或提高预算。应保留原始执行身份，有限枚举 SDK 对象形态，排除额外 args/env/管道；使用安装版 SDK 的真实对象工具输入建立独立回归。

`ClineCodingAdapter.resume` 的 native 分支在回收遗留 TOOL/CODE 之前调用 `recoveryFence`；后者却要求所有 start intent 已停止。Worker 在工具中断时，这两个条件互相阻塞。应区分“先隔离旧 Worker 的恢复所有权”和“允许下一次推理”：前者必须保留所有未知状态，后者仍要求独立停止证明及完整工具边界。不能把 fence 当作停止证明。

`NativeCodingWorkspaceService.authorize` 对已有不可变工具回执仍调用 `startIntent`。更换 fence 后，原来的 start fence 与当前 fence 不同，合法回放可能被拒绝。最小修复应在身份、原输入 Hash、授权和停止证明均验证后只回放，不能重新执行或重复扣预算。先建立真实 A2 测试，再进行 Worker 中断实测。

## 边界与尚未通过项

Java 不决定修复步骤，不生成正确源码。SyntaxError、KeyError、NameError、TypeError 仍由同一原生 Agent 根据真实反馈处理，尚无本分支完整 Runtime 的 A–G 模型验收结果，不能将确定性测试称为自主修复成功率。

SDK 0.0.93 的公开包未导出官方 routing resolver；仅将该版本官方模块原样 vendoring，并记录来源及 SHA，未混用最新主线。低层 Agent 的固定 registry 与 ClineCore 动态工具装配存在版本接口差异。Java MCP 工具代理及动态 Schema 尚未接通；预留 Node provider 不等于完整 MCP 支持。

新 profile 三端默认关闭，另有独立新任务准入开关。回滚先关准入，保留现有任务恢复、停止和归档读取，确认无活跃原生任务后才回滚组件。没有新增产品数据库、版本系统或 Agent Loop。
