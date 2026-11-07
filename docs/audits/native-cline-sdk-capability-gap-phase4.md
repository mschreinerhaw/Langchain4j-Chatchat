# Phase 4 原生 SDK 能力差异：P0 先行审查

固定 SDK 0.0.93 / 官方提交 `1a5c3f84e0933268a9dba14a55a6383a6578414d`；包、锁文件和原始源码校验复用 [既有基线](cline-native-source-baseline-20261107.md)，不混用最新主线。本轮实现先限定失败收敛，MCP、Skills、配额及语言扩展仅列差异，不开展 P1。

| 能力 | 官方实现与 Runtime 对照 | 分类与 P0 处理 |
| --- | --- | --- |
| Agent Loop / 完成 | `agents/src/agent-runtime.ts`；`native-runner.js` 创建一个 Agent，普通最终回答经 completionGuard 收敛 | 已复用；不加 Loop、提交代理或 Java 终止决策。SDK 默认 submit_and_exit 关闭，不按名称判缺失 |
| read_files | `core/src/extensions/tools/definitions.ts` 与原生 file executor；`native-workspace.js` 提前校验路径，再调 SDK | 继承但受控差异：任务视图、文本/体积约束；保留隔离和越界拒绝 |
| search_codebase | 原 SDK search executor，经同一 workspace 视图 | 继承但范围/截断受治理；不增加自制搜索算法 |
| Editor / Patch | 原 SDK createEditorExecutor、createApplyPatchExecutor、computePatchChanges；Runtime staging、持久 ACK、不可变树 | 原算法继承；失败不部分发布。A/C/E 证据未指向算法缺陷，不改 SDK |
| Shell | 原 SDK 参数正规化与 shell executor；Runtime 逐正规化命令冻结并派发 Sandbox | 受控差异：每个命令占一次 CODE；NONE 网络、源码只读、原 75 秒上限。保留已验证 pipefail 策略，不扩大宿主权限 |
| Tool Call | SDK 并发 prepare、顺序执行；Runtime 在实际 execute 前准入，命令队列串行 | 已修复并有原始任务证据；不重构工具系统 |
| Context | SDK basic + stateful compaction；`sdk-adapter.js` 保护最新完整工具轮 | 部分继承；保存的压缩投影会去掉较早验证，当前额外记忆只有 lastExecution。P0 用已有 prepareTurn 投影权威验证与原预算，不创建摘要模型 |
| Checkpoint / Restore | SDK snapshot/restore/continue；Java/Silo 保留合同、fence、累计预算与回执 | 已有技术恢复，完整交付未通过；P0 修正 workspace 重载时验证状态刷新，不改租约或增加预算 |
| 错误反馈 | 同一 Agent 读取真实工具结果后决定修改 | 能力有实证；A 测试不正确、C 不收敛、E 后续源码未重新验证必须分别整改 |
| Skills | SDK discovery/loader + 既有发布授权 | 部分继承，长任务/撤权整链不足；暂停推进 |
| MCP | SDK createMcpTools provider 端口 | Java 生产动态 Schema 治理缺失；暂停推进，不通过 Sandbox 网络绕过 |

## 工作区约束

32 文件、256000 源码字节、64 个历史版本是当前 Runtime 有界治理配额，不是 SDK 原生算法上限。后续可由管理员按任务类型冻结到版本化 profile，配合 Silo 索引/按需读取/分块减少全树传输；模型不能自行提高配额。此处仅为后续设计方向，尚未实施或验收。

跨任务、路径穿越、符号/硬链接、未授权绝对路径和只读测试限制是安全边界；不能与配额一起删除。ASCII 可移植路径和纯文本协议是当前 Adapter 功能限制，不应包装为原生 Cline 的完整文件能力。更大项目、二进制资产和 Unicode 文件名仍有缺口。

模型初始只获取文件索引，按需原生读取；P0 不注入项目全文，只增加有界验证事实。全树仍在每次持久提交和执行快照中传输，存在大项目成本，暂不扩大范围优化。

## 执行环境

已验收镜像有 Python 3.11.18、Node 24.21.0 和固定 SDK 包。Python 通用任务及 Node 执行器实际使用过；完整 Node 项目构建/依赖管理、Java JDK/Maven 项目未通过专项验收，不宣称已支持多语言。NONE 网络环境不会任意联网安装依赖；未来应复用现有发布环境与固定镜像能力进行版本协商，不让 Worker 执行宿主 Shell。本轮不扩建镜像或语言环境。

## 本轮实施门槛

先提交 [三项根因报告](native-coding-phase4-failure-analysis.md)，再修正验证/预算事实投影与重载，保留旧业务路径、原生 SDK 算法及全部原预算。A、C、E NameError 原始合同的真实完整任务通过前，不推进本表 P1 缺口。组件测试、已有源码通过或技术恢复都不能替代该门槛。
