# Cline 重复机制整改台账

审查 main `0693c15fd`；生产实现 `3e28607b9`；本机/镜像实测 SDK **0.0.93**；日期 2026-11-05。
最低支持版本列表示本轮**实际验证版本**，不推断首次发布版本。

| 自研组件/方法 | 原职责 | SDK 对应公开能力 | 实测最低版本 | 上游依据 | 是否重复 | 动作/实际结果 | 安全影响 | 必需测试 | 删除条件 |
|---|---|---|---|---|---|---|---|---|---|
| `sdk-adapter.editProgram` → `prepareProgram` 预演及 expected 比较 | 自研替换后再核对 SDK | `createEditorExecutor` | 0.0.93 | 固定 tag Editor；见上游表 | 完全重复且语义不一致 | 已删除预演和结果再比较 | 固定 scratch `program.py`，父 hash/大小/取消保留；模型路径不传入 fs | CRLF/LF、歧义、部分失效、陈旧父 hash、1k/10k 行 | 本轮已满足 |
| `python-project.proposeProject` 的 `split/replace` | 多文件逐项文本替换 | 同一 SDK Editor | 0.0.93 | Editor | 完全重复 | 已替换为 `await editProgram` | 项目路径、大小、大小写冲突、只改 source 不改 Runtime adapter；空文件初始化保留 | 跨模块、前次修改保留、路径越界、取消、真实联机 | 本轮已满足 |
| `runtime-model.stream` + `completionGuard` native 提示 | 把截断折叠 stop 并要求再生成 | Agent max-tokens recovery / 原生 invalid JSON tool feedback | 0.0.93 | CHANGELOG 及安装包行为测试 | 部分重复 | 已保留 max-tokens；native 不再使用私有 completionGuard | 实际 stream 逐次准入，SDK 错误重试默认禁用，Runtime 修复次数上限继续有效 | 截断→原生恢复、每次计费、一次工具执行、耗尽拒绝 | native 分支已收敛；旧模式等待退役 |
| `CodeHistory.protectedFragments/forbiddenFragments` | 历史字面保护状态 | SDK checkpoint + Java 版本/证据 | 0.0.93 | 不存在能证明语义正确的字符串 API | 过时状态 | 新检查点不再输出；旧字段读取时丢弃 | 不删除 source/hash/验证状态；不将字符串保护当行为测试 | 旧 checkpoint、合法再次修改、rollback、源 hash 篡改 | 本轮已满足 |
| `CodeHistory` 各版本 raw fragments/forbidden | 计算 patch 身份的临时内容 | 版本摘要即可；SDK 管 canonical transcript | 0.0.93 | 本地调用核对 | 部分重复 | 记录时即转换为 editCount/patchHash；旧结构单向转换 | 完整代码仍在不可变 artifact 和当前版本；摘要不证明正确性 | pending/failed/validated 各状态及恢复 | 本轮已满足 |
| `prepareProgram` 与非 native `afterModel` 计费 hooks | 旧协议、旧检查点及旧运行模式 | native Editor/stream 预算边界 | 0.0.93 | SDK AgentModel | 部分重复，当前兼容仍需保留 | 明确保留；native 主路径已绕开 | 贸然删除会损坏历史恢复或造成漏计费 | 老 checkpoint、旧模式和逐次 transport attempt | 清点并耗尽旧任务，明确旧模式退役后删除 |
| `sdkPrepareTurn` 表示转换和技术 binding | 在 SDK 压缩前后携带 Runtime 授权与代码身份 | `createContextCompactionPrepareTurn` / `createCompactionStateAwarePrepareTurn` | 0.0.93 | SDK 架构/公开 API | SDK 压缩已复用，领域保护必须保留 | 保留；没有新增摘要、消息历史或模型循环 | canonical transcript 保留；绑定/最新诊断/完整报告保护；估计不是计费 | 原目标、授权、错误、报告、prefix-hash 恢复及失效 | 仅在 SDK 接受本项目 portable 表示且领域绑定有等价保证时再缩小 |
| `runner.forward` / tool timing | Runtime 进度投影 | Agent hooks/events/durationMs | 0.0.93 | Agent hooks | 非第二套执行证据；部分观测可优化 | 已隔离 event 失败；未新增遥测状态机或批处理协议 | admission、结算、checkpoint、结果不吞错；失败本地可追踪 | 监控中断仍执行；准入拒绝仍不执行 | 如需批处理，先完善 Java 协议、顺序/重放/容量验证 |
| Java Durable / Python bridge / Artifact store / Collector | 状态、租约、权限、预算、停止和交付证据 | SDK Session 无等价平台授权语义 | 0.0.93 | 完整 Core 不等于平台任务权威 | 必须保留 | 保留 | 租户/用户隔离、hash、MCP、固定 deadline、未知结果不重放 | Java 29 场景、签名回调、真实联机 | 不属于可删除的 SDK 重复实现 |
| `skills.js` / tool catalog | 任务授权、原生包完整性和按需加载 | `parseSkillConfigFromMarkdown` / Agent tools | 0.0.93 | SDK Tool/Skills 公开能力 | 原生解析已复用，范围校验必需 | 保留 | 不打开默认 shell、网络、数据库、宿主文件权限 | Skills 按需披露、包 hash、越界/符号链接、未授权工具 | SDK 宿主隔离可证明等价前不能删除 |

上游链接和当前状态集中于 [上游调查](cline-upstream-issues-prs.md)。
复现命令、实际结果及未覆盖风险见 [回归](../testing/cline-sdk-native-regression.md)。

## 代码和状态统计

以 main 到生产实现 `3e28607b9` 的 `git diff --numstat -- coding-agent-node/src` 为准：

- 修改生产文件 5 个，删除文件 **0** 个；新增 **37** 行，删除 **28** 行，净 **+9** 行。
- 增加的校验解释、旧版本单向转换和遥测失败隔离超过删除量；不把净减行数作为成功条件。
- 活跃 `CodeHistory` 私有字段减少 **2** 个；每版本不再存储 **2** 个原始文本数组。
- 没有新增 Agent、执行状态机、模型调用入口、工具或 Java 回调 endpoint。
- native 模型响应 completionGuard 从自研反馈转为 SDK 内置恢复；兼容模式仍存在，不将它计为彻底删除。
- 单轮模型、Python、checkpoint、event 的回调数没有通过协议层改动强行减少。
- 通用 Node 回归从 **210** 增至 **216**；新增 **10** 个真实 Docker 故障测试；Java 本轮 **29** 个。

部署工具及回归 fixture 的新增行数单独统计，不混进生产逻辑缩减量。
剩余风险：旧协议保留期、超大 canonical checkpoint、语义级回归、外部模型未知用量、长任务性能统计。
