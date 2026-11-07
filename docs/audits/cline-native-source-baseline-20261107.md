# Cline 原生编码工具源码基线与执行流程报告

日期：2026-11-07。Runtime 对照基线：目标分支 `fix/runtime-data-exploration-delivery-20261106` 的 `e9eda1b9afd86bb2cecb9ade833f3b3023b38929`。先形成源码对照，再开展候选实验；本报告不证明候选的业务效果。

## 版本证据

`coding-agent-node/package.json` 和 package-lock.json 将 @cline/sdk、core、agents 锁定 0.0.93，传递依赖 llms、shared 同为 0.0.93。重新下载五个 registry tarball，各自 SHA512 同时匹配 lock 和 npm registry integrity；发布包 629 个文件与当前 node_modules 对应文件逐字节完全一致。详见 [机器证据](cline-sdk-0093-package-baseline-20261107.json)。

npm 元数据没有 gitHead。五包的 npm SLSA 发布证明 payload 均指向官方仓库提交 [`1a5c3f84e0933268a9dba14a55a6383a6578414d`](https://github.com/cline/cline/tree/1a5c3f84e0933268a9dba14a55a6383a6578414d)，发布工作流 sdk-publish.yml、GitHub Actions run 38367577624。已 clone 官方仓库并 checkout 该提交。校验范围是发布载荷、包完整性与安装内容；尚未独立验证 Sigstore 证书链，不声称重新构建字节可复现。

最新 main 仅记录为 `94eecbbe1a2f899c66b118d13656651b0a884e1f`，不从该提交导入任何接口。以下上游路径全部相对于上述 0.0.93 对应提交的 `sdk/packages/`。

## 原生能力矩阵

| API / 能力 | 上游源码及函数位置 | 职责与调用链 | 必须保留的语义 |
|---|---|---|---|
| Agent | agents/src/agent-runtime.ts:530，623，627 | Agent 是 AgentRuntime 别名；run/continue → execute → model.stream → executeToolCalls | 自主选择工具、消费真实回执；没有独立的“自动修复 Agent” |
| 工具结果和失败 | 同文件:2333、2395、2530 | prepareToolExecution → executePreparedTool → tool-result 写回 messages → 下一原生循环 | 参数正规化、策略、before/afterTool；异常成为 isError，不自动发明修复代码 |
| ClineCore | core/src/ClineCore.ts:99、212、285 | createRuntimeHost → start → host 的 session runtime；runtime/orchestration/runtime-builder.ts:141 组装内置工具 | 高层宿主、会话与工具装配，不等同于低层 Agent 构造器 |
| createDefaultTools | core/src/extensions/tools/definitions.ts:915 | 按 enable 标志和已提供 executor 建立 AgentTool | enableBash/Editor 默认 true，ApplyPatch 默认 false；只提供受控 executors 才安全 |
| createDefaultExecutors | core/src/extensions/tools/executors/index.ts:114 | file-read/search/bash/web-fetch/editor/apply-patch 的 Node 默认实现 | 默认 shell 和网络执行器不能直接带入 Runtime |
| createBuiltinTools | core/src/extensions/tools/index.ts:194 | 默认 executors 与 host overrides 合并，再 createDefaultTools | override 是原生支持的 Adapter 扩展点 |
| createEditorExecutor | core/src/extensions/tools/executors/editor.ts:220 | create/replace/insert；唯一 old_text、行尾正规化、生成 diff | 原生替换算法可复用；不是模型可见工具授权机制 |
| createApplyPatchExecutor | core/src/extensions/tools/executors/apply-patch.ts:399 | computePatchChanges → parser → applyChanges，支持多文件/增删/移动 | 复用原生 parser 和执行器；跨文件须治理原子提交与失败回滚 |
| read_files | definitions.ts:268；executors/file-read.ts:220 | Zod 参数正规化 → 每文件读取/错误数组；支持一基闭区间、分页及输出上限 | 工具结果数组中的 success=false 也要给模型看到，不能只看顶层 isError |
| search_codebase | definitions.ts:364；executors/search.ts | 多 queries → cwd 下 rg 或 fallback 正则搜索 | 内容搜索范围与读文件范围一致；不能扩展到宿主文件 |
| run_commands | definitions.ts:496；executors/bash.ts | 原生命令 Schema → shell executor / timeout / stop controller | 原生是一般命令能力；Runtime 候选仍用 Python Sandbox，不暴露宿主 Bash |
| 模型工具路由 | core/src/extensions/tools/model-tool-routing.ts:75、131；runtime-builder.ts:141 | provider/model/mode 规则 → enable flags → createBuiltinTools | openai-native 或 codex/gpt act 用 Patch、禁 Editor；Qwen 不命中。低层 Agent 不会自动调用该规则 |
| Context Compaction | core/src/extensions/context/compaction.ts:267、745；session/models/session-compaction.ts | prepareTurn 基础压缩和有状态包装 | 压缩不能当持久化代码存储或权限依据 |
| Checkpoint / Restore | agents/src/agent-runtime.ts:669、690；core/src/ClineCore.ts:561 | snapshot 外部保存 → restore(messages) → continue | 低层 restore 重置 SDK usage，保留工具/模型/hooks/subscribers；Runtime 必须继续控制累计预算与 operation 回执 |
| 工具失败后修复 | agent-runtime.ts:945、972、2580 | 异常或 success=false 回执留在同会话，模型下一轮选择读/搜/编辑/执行 | SDK 提供循环和编辑器，修复成功仍由模型及运行反馈决定 |

源码永久链接根为 [0.0.93 官方源码](https://github.com/cline/cline/tree/1a5c3f84e0933268a9dba14a55a6383a6578414d/sdk/packages)。不将 README 的最新行为当作该版本实现。

## Runtime 对照与缺陷清单

Runtime 路径相对于本仓库。下列差异必须通过实验度量，不能仅凭工具改名判定损失。

| 项目 | Runtime 调用链 | 接口和行为差异 | 已有证据 / 能力结论 | 最小候选与风险 / 验收 |
|---|---|---|---|---|
| Agent / ClineCore | runner.js:700 config、1084 new Agent、1087 restore、1158 continue | 使用低层 Agent，自行装配 authorized tools；不经高层 ClineCore 的默认工具路由 | 原生循环存在；“没有 ClineCore 所以没有原生能力”不成立 | 保持同一 Agent；记录最终实际 tool schema。不得换循环 |
| 源码读取 | runner.js:637 code_read → code-read.js；native-skills.js:135 read_files | code_read 要 exact hash、行范围；原生 read_files 当前只读 Skill 清单，不能读 program.py | 原任务有读取和 patch 反馈，但未形成原生读/搜/改统一工作区 | 对 task 代码建立统一原生读取视图；Skill 原授权仍独立 |
| 搜索 | runner.js:19、21 允许工具集合；native-skills.js:149 enableSearch=false | 代码没有原生 search_codebase；code_read 不等同内容搜索 | 源码确认缺少搜索通道；效果损失尚需 A/B/C，不能把全部失败归因于此 | 原生搜索 executor 限于独立 task 代码目录；负向检验路径/链接 |
| Editor | runner python_execute repair → sdk-adapter.js:11 editProgram → createEditorExecutor | oldText/newText + baseCodeSha256 + parentCodeVersion；每次临时单文件应用后清理；不是新的文本替换算法 | 已复用原生 Editor；模型承担自定义版本参数和分段协议 | 模型直接使用原生 Editor Schema；版本绑定由 host 管理；旧路径保留 |
| Patch | sdk-adapter.js 未装配 createApplyPatchExecutor | 模型不能使用原生多文件 Patch，只能 repair.edits / project.edits | 旧 flash-1 曾提交 24,182 字符 replacement 超 16,000 限额；仅证明该拒绝，不证明 Patch 必胜 | 候选按路由选择 Editor 或 Patch，复用原生 parser，不同时随意开启两套近似工具 |
| 多文件 | sdk-adapter.js:40 resolveWorkspaceFiles、restoreWorkspaceFiles；code-project.js | 已有多文件 proposal/repair 协议与代码包装；不能说 Runtime 完全没有多文件能力 | 存在代码功能；模型自主协作效果未知 | 持续代码目录、冻结树清单，Python 执行原始入口；不让模型重写包装器 |
| Python 执行 | runner.js python_execute → Java CodingPythonExecutionBridge → MCP Sandbox | 新 Sandbox、冻结输入、代码 Hash、结果合同；无直接宿主命令 | 已有真实执行；退出0缺 rows.jsonl 仍拒绝，符合合同 | C 仍使用此执行边界；A 的默认命令环境差异需明确 |
| 错误反馈 | CodingExecutionFeedbackService.java:82 finish、response | 分类、safe message/stack、base hash、修复次数；原始 stdout/stderr 不全部披露 | 六次真实任务0份完整报告：KeyError/NameError/Decimal TypeError/SyntaxError/缺输出/无效大补丁。含生成代码和输出问题，不能统称工具缺失 | 同一错误源码、同预算比较首次有效代码、修复轮数、完整交付 |
| Compaction | sdk-adapter.js:97 sdkPrepareTurn；completion-context.js | 调用原生 basic，再保留代码绑定、技术错误、报告等必要上下文 | 有原生压缩；上下文治理代码较多，但删除是否有益尚无因果证明 | 候选用短文件视图与现有 checkpoint，测试压缩后 source 可读和版本正确 |
| Restore / 预算 | runner.js checkpoints + DurableCodingTaskService.java:198、260 | Java fencing、完整 tool pairs、operation stop proof；SDK usage reset 后由 Java累计 | 已有治理，不能为了原生工具直接绕过 | 相同 checkpoint/operations 恢复；冻结代码不能由压缩文本重建 |
| 代码归档 | CodingCodeArtifactStore.java:30 commit → RuntimeAssetArchive.code / scoped evidence | 现有不可变 source、script、SHA、Silo 归档和 durable private state | 无需先增加第二套 Git/数据库/Checkpoint 存储 | 复用 Silo 对象和原 journal 的 current manifest 指针；工作目录只是可丢弃缓存 |

## 原生执行器的安全实情

0.0.93 的 Editor `resolveFilePath`（editor.ts:42）明确接受绝对路径，即使 restrictToCwd=true；Patch 同样在 apply-patch.ts:59 接受绝对路径。文件读取 executor 也不是租户权限检查器。必须在任何原生 IO 之前校验 task 路径、所有父目录和软链接，并在容器中隔离；不能直接放开默认 tools。

Patch 的 computePatchChanges 本身会读文件，故不能先调用它、再判断读权限。候选必须在 parser 读文件之前处理每个文件 header 的路径；保持原生编辑/补丁算法，host 只验证资源边界。多文件写入应在私有 staging 完成，全部通过后才能提交不可变树清单；拒绝时原 current manifest 不变。

## 实验与上线门槛

A/B/C 采用同一模型标识和参数快照、同一源码/数据 Hash、同一原始需求与预算。A 使用该版本原生工具，B 保留现有协议与 Sandbox，C 仅增加受控原生代码工作区。任何环境、可访问文件、工具描述、执行反馈差异均单列。A 的 shell 权限不得扩展到业务宿主。

至少覆盖八类规定样本，模型原始回执仅保存在私有受控证据区；仓库公开工具名、Hash、版本、分类反馈、usage、时间和验收结果，不发布私有提示词/凭据/业务原始数据。确定性 executor 测试只证明工具语义和安全边界，不能替代模型实验。

只有 C 在重复样本中明确优于 B，且 Java 版本绑定、恢复、预算、取消、权限和归档验证均通过，才提出默认路径迁移。本次基线提交不改变生产代码；后续候选默认关闭，不能以一次成功推进上线。
