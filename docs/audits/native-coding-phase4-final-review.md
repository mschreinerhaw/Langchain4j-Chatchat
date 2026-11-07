# Phase 4 P0 最终审查与未完成项

2026-11-07（Asia/Shanghai）。审查基线 `ca0f7a47d`，独立分支 `fix/native-coding-phase4-delivery-20261107`，原合入基准 `167dfedf5`。**本轮任务一及任务二 P0 已形成代码、真实模型、独立验收和停止证据闭环；整个 Phase 4 尚未完成 P1 验收，不满足合入 main 的全部门槛。**

## 提交与根因

| 提交 | 内容与证据 |
| --- | --- |
| `0be922534` | 先行提交 A、C、E NameError 的逐轮根因及官方 SDK 对照；明确 Java 已保留 lastVerification，C 不收敛与 E 最终版本未验证不同；历史原请求正文缺口明确列 F 类 |
| `e107dffff` | 唯一产品代码 P0：通过 SDK 既有 prepareTurn 提供当前版本验证、失败诊断及原预算事实；lost-ACK 后先重载再投影；v2 成功必须有可用结果和停止证明 |
| `94287c9fd` | 首三项真实交付、独立历史资产回放及白名单观察入口 |
| `808ce8266` | 隔离 Worker 的验证通过后崩溃恢复控制器；不是产品规划器或修复流程 |

产品改动仅在 Node 三个文件（含新纯事实模块），未改 SDK/vendored、Java、Sandbox、模型、原预算或任务样本；未创建新 Agent Loop、编辑算法、摘要模型、版本数据库或 checkpoint 存储。既有 Java/Silo 验证回执继续作为权威依据，模型看到的状态不是新的执行授权。旧 `python_execute` 与 v1 完成/授权路径保留；共用 native hook 的重载时序提前已覆盖既有回归。

根因全文：[逐项审计](native-coding-phase4-failure-analysis.md)；[SDK 能力差异](native-cline-sdk-capability-gap-phase4.md)；[设计](../design/native-coding-phase4-design.md)。没有重做已解决的旧资源授权逻辑，没有新增金融专用修复。

## 实际验收结果

| 项目 | 实际结果 | 证据 |
| --- | --- | --- |
| 组件复现 | 修改前验证/STALE 两项失败；重载顺序单项失败，均保留日志 Hash | [组件记录](../testing/evidence/native-coding-phase4/component-tests.json) |
| 完整 Node 套件 | 318 通过、0 失败、1 Windows 链接权限跳过；构建通过 | [回归报告](../testing/native-coding-phase4-regression.md) |
| A、C、E NameError | 各三次原模型、原预算的真实完整交付通过 | r1 三项 + r2/r3 两轮原八项 |
| 原八项完整重复 | r2 8/8、r3 8/8；最终源码各 8/8，所有只读/额外约束满足 | [r2](../testing/evidence/native-coding-phase4/after-r2/summary.json)、[r3](../testing/evidence/native-coding-phase4/after-r3/summary.json) |
| 模型实际可见事实 | r2/r3 每次已准入模型均有工具披露/Schema 和白名单事实观察；FAILED/STALE/PASSED 可核对 | 每项 `*-model-facts.json`、[控制检查 r2](../testing/evidence/native-coding-phase4/after-r2/control-checks.json)、[r3](../testing/evidence/native-coding-phase4/after-r3/control-checks.json) |
| 同任务真实 Worker 恢复 | 同 session、同合同/截止时间、同验证操作；原 CODE 未重复，最终交付 | [恢复与安全](../testing/native-coding-phase4-recovery.md) |
| 真实 Sandbox 安全 | 六项只读测试通过、完整归档及独立复核；无宿主 Shell/Socket/网络开放 | 安全任务 `native-cbc5c8c0bdc03fbbfc9794f9ff03cecba57c8b537c5a20cd` |
| 历史归档 | 新任务每一历史版本均由 UserAsset 重新下载、核对原字节及 manifest，再独立回放；原任务用量/终态不变 | 每项 `*-history.json` 与恢复/安全历史证据 |
| 最终停止 | 70 个已准入任务均终态、无未核对启动；Worker 活动 0、固定 Sandbox 运行数 0 | [停止普查](../testing/evidence/native-coding-phase4/final/phase4-final-stop-census-public.json) |

生产代码始终为 `e107dffff`；原请求/fixture/harness Hash、模型连接指纹、授权、实际工具 Schema、预算限额逐项相同。隔离 Worker 的实验入口版本不同已单独记录，透传请求、不改变模型决策。r1 health 字段继承基础镜像的旧 build 值，已通过 dist 字节 Hash 与 OCI revision 核实；r3 再次核对实际运行 dist 与 P0 本机构建相同，不能只根据 health 字符串判断源码。

完整任务不等于一次 exit=0：均要求原 Agent 自主结束、Runtime 交付、独立源码验收、原执行停止、归档重读。Runtime `INCONCLUSIVE`/`independentProjectAcceptance=NOT_EVALUATED` 未被人为改为 PASSED。历史失败任务保留，无结果覆盖、删测试或替换旧成功版本。

本轮新真实任务为三项首轮、两轮八项、Worker 恢复一项、安全一项；原失败与组件失败均完整保留。代码及每文件 Hash、原任务 ID、命令身份、真实退出码、stopProofRef、模型/工具事件及历史资产 Hash 均随上述证据入库。提交的新增白名单证据未导出私有提示词、凭据、模型自由文本或不必要业务原文。

最终执行 `node coding-agent-node/experiments/phase4-check-evidence.mjs .`，只读核对 19 次固定任务、103 次模型准入、58 次原任务 CODE、55 个历史版本、84 次实际请求事实及恢复/安全专项，并逐项验证最终 PASSED 与同 manifest 的原声明回执及 stopProofRef 一致。原始证据字节 Hash 汇总见 [证据清单](../testing/evidence/native-coding-phase4/all-evidence-hashes.json)；核对还覆盖七份交付报告的相对链接。产品 src 自 `e107dffff` 后没有变化，其他两个原工作树保持干净。

## 性能与剩余验收

同固定八项的原第四批为 5/8 完整、7/8 源码、52 次模型/257427 已知 Token；P0 r2 为 8/8、43/217265，r3 为 8/8、41/198947。样本有限且无随机交叉设计，不宣称统计意义的因果性能改善。A 的 r2 Token 高于旧失败样本，前两次都用满模型上限，保留此风险。CPU/内存/成本实际计量不完整，未用原零字段冒充零消耗。[完整对照与测量定义](../testing/native-coding-phase4-comparison.md)。

以下仍未完成，**不得把 P0 通过写成整个 Phase 4 完成**：

- MCP：Java 生产动态治理绑定、真实发现/调用/错误/撤权/恢复及跨用户/跨租户/过期权限负向验收。
- Skills：真实发布 Skill 的长任务、压缩后再加载、撤权及 Worker 恢复组合整链；本轮未扩大既有能力。
- 恢复：API/MCP/多服务重启、执行中崩溃及压缩后崩溃组合故障在本轮完整交付下的专项；本轮通过的恢复样本 compactionCount=0，不能混称组合验收。
- 原生对照：官方 CLI/客户端同条件重复实验未执行；SDK 参考只保留历史证据及已披露环境差异，不能冒充新的 A/B/C 因果对照。
- 更大工作区、更多语言、生产环境成功率及资源实际计量仍有差异/缺口；审计矩阵已明确分类，没有因样本通过移除安全边界。

原八项固定集门槛现已重复通过，后续 P1 可以据此单独开展；本 P0 分支可供代码审查，尚不替代上述完整 Phase 4 合入审查。本次没有合入 main，没有替换现有业务入口或变更生产服务。

## 回滚

先停止新准入；核实原 MODEL/TOOL/CODE 独立停止、无 reconciliationRequired、Worker activeSessions=0、相应 Sandbox 不运行。保留所有失败、Silo 历史与用量，不重置或重新结算原任务。

隔离运行回滚使用保留的旧 Worker `runtime-native-acceptance-worker-before-phase4-p0` / 镜像 `sha256:1b739bcd27cd59027efbf2c707f179126e767d108b6a6a2e0d9ebd636eee687f`；API/MCP/Sandbox 未改，无数据库迁移。凭据仍通过原私有配置和 stdin 注入，不写命令正文或报告。源码回滚针对产品提交 `e107dffff`，先复核后续文档/测试提交并构建回归，不删除证据；本报告提供回滚方案，未执行回滚。
