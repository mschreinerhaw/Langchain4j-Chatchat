# Cline 上游版本、Issue 与 PR 调查

查询日期：2026-11-05。审查 main `0693c15fd`，实施 `3e28607b9`；实际 SDK **0.0.93**。
版本核验命令：`npm.cmd view @cline/agents version`、`npm.cmd view @cline/core version`、
`npm.cmd view @cline/sdk version`，三者 latest 均为 **0.2.0**。
部署镜像另运行 `node dist/sdk-packages.js` 核对五包，均为 0.0.93。

| 来源 | 已核实状态及适用性 | 本轮处理 |
|---|---|---|
| [0.0.93 固定 tag 的 Editor 源码](https://github.com/cline/cline/blob/sdk/sdk/v0.0.93/sdk/packages/core/src/extensions/tools/executors/editor.ts) | 公开 `createEditorExecutor`；唯一锚点替换、现有行尾处理由 SDK 实现 | 直接复用；本地与镜像内实测通过，无需升级 |
| [SDK CHANGELOG](https://github.com/cline/cline/blob/main/sdk/CHANGELOG.md) | 历史记录包含 Editor 行尾修复、无效参数反馈、bounded max-token 恢复和压缩；main 的后续修改不能视为安装包已具备 | 对需要的行为使用真实 0.0.93 测试，不仅依据文档 |
| [SDK v0.0.94](https://github.com/cline/cline/releases/tag/sdk/sdk/v0.0.94) | 已发布；包含模型流错误恢复、插件隔离等变更 | 不为使用既有 Editor 混装升级；Java 模型桥不是 SDK 内置 provider transport |
| [SDK v0.0.95](https://github.com/cline/cline/releases/tag/sdk/sdk/v0.0.95) | 已发布；包含部分 provider 参数兼容修复 | 当前模型由 Java 桥接，未据此宣称能修复本任务 |
| [SDK v0.2.0](https://github.com/cline/cline/releases/tag/sdk/sdk/v0.2.0) | 已发布且 npm latest；会话重连、请求重试、压缩估算等存在变化 | 保持 0.0.93；升级须独立做 bridge/账本/恢复兼容门禁，不能直接上线 |
| [Issue #11793](https://github.com/cline/cline/issues/11793) | 查询时 Open；报告 apply_patch 大上下文模糊匹配的 CPU/内存风险 | 当前使用 Editor，不新增模糊匹配或把该问题误判为本地 Editor 缺陷 |
| [PR #11915](https://github.com/cline/cline/pull/11915) | GitHub API 实查 `state=open, merged=false, merged_at=null`；涉及大型上下文匹配 | 不 cherry-pick，不将未合并提案视为稳定修复 |
| [Issue #10637](https://github.com/cline/cline/issues/10637) | 关于自动压缩开关；与本工程主动配置的 prepareTurn 不等同 | 本地验证显式 compaction 配置、canonical transcript 和受保护上下文；不引入自研摘要 Agent |
| [SDK 架构](https://github.com/cline/cline/blob/main/sdk/ARCHITECTURE.md) | agents 管循环，core 管会话和压缩投影 | 当前通过公开扩展点组合；不把完整 SessionHost 的能力误称为裸 Agent 已有持久化 |

## 本地兼容项登记

| 触发与范围 | 复现/验证 | 安全影响与退出条件 |
|---|---|---|
| SDK Editor 前的自研精确预演拒绝 CRLF/LF 补丁；属于本工程集成问题 | `sdk-reuse-regression.test.js` 1,000 / 10,000 行换行测试；旧实现会抛锚点失配，新实现通过 | 删除重复预演；父 hash、长度、取消和固定路径继续保留，无需上游补丁 |
| Runtime 把授权输出截断折叠成 stop；属于本工程事件映射 | 同文件 `authorized output truncation...` 和 `runtime-model.test.js` | 使用 SDK max-tokens 恢复；每次请求有新 admission/settle，未知故障仍不可重试 |
| prepareProgram 的旧协议读取 | 既有 `recovery.test.js`、`code-lifecycle.test.js` 和 Java Recovery 16 场景 | 仅保留兼容入口，待旧协议生命周期结束后删除，不能为了减行数破坏历史恢复 |
| SDK 版本升级 | 本轮没有将 0.2.0 当成已完成兼容测试 | 需记录每次真实请求、未决副作用、旧检查点和压缩行为，独立验收后才能更改锁文件 |

未确认本轮问题是 SDK 本身缺陷，因此没有替用户向上游发送 Issue/PR。
可提交的最小复现已经保存在 `sdk-reuse-regression.test.js`、`sdk-native-capabilities.test.js` 和
`integration/sdk-sandbox.mjs`；其中模型为确定性 fixture 的测试均明确标注，不能当作外部供应商故障证明。

命令及结果见 [回归报告](../testing/cline-sdk-native-regression.md)。
风险：公开 upstream main 会继续变化，本表只代表查询时状态；0.2.0 未进行完整生产兼容验收。
