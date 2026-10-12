# Runtime 固定流程阶段交接修复

## 确认的问题与职责边界

联机任务 `472470d3-d0fc-3a2b-b41b-e686d5c788fa` 在分析前失败。发现桥输入 `stage=template`，实际返回 `asset_query_result.v1`，既有 DAG 却要求该节点提供 `$.templates[0].templateId`。重试改成资产阶段后，未补齐模板节点，模板来源校验拒绝执行。

根因不在业务指标选择，而在系统工作流编译：

| 位置 | 证据及影响 | 修复 |
| --- | --- | --- |
| `InterpretationPlanOptimizer.materializeTemplateDiscoverySteps` | 只补缺失模板节点；已包含模板节点时，不补发布契约声明的前置资产阶段，导致资产候选被模板边契约消费 | 读取注册工具权威 `inputSchema.properties.stage.enum`，仅对声明 `asset` 与 `template` 的发现桥展开固定资产→模板依赖；保留查询范围和原依赖，重复编译不重复增加节点 |
| `InterpretationPlanOptimizer.declaredTemplateDiscoveryTool` | 只读取执行方 `templateDiscoveryTool`；线上发现方声明 `executionTool`，执行方没有反向声明，重试无法找到授权伴随节点 | 同时识别发布者发现方声明；须协议兼容、非分组父能力且关系唯一。歧义不猜测，不按工具名称匹配业务 |
| 资产阶段协议 | 重试提交内部语义 `asset_selection`，发布输入 enum 仅接受 `asset`、`template` | 在该发布契约内规范化为 `asset`，不向工具提交未声明阶段 |
| `ModelNativeAnalysisHarness.recordIntent` | 模型更新正文但提交旧 publication 引用，引发 `Publication report or evidence snapshot does not match`，最终未交付 | Runtime 根据实际接收正文及本轮授权证据生成版本绑定；旧模型引用仅作为审计，不拥有当前用户交付权限。保留后续内容篡改校验及外部操作独立授权 |

工具名称、DEV 环境、CPU 等检查项不参与上述生产控制流。未新增任意查询入口、模型资产发现能力或质量发布门槛。继续复用原 `InterpretationPlan`、编译插件、候选审查、权限过滤、绑定及取数机制。模型分析仍限定于这些流程交付的授权模板数据。

## 验证和部署范围

阶段与执行回归 225 项通过；协议别名规范化后受影响编排测试再次通过。使用实际服务器包作为依赖运行部署包测试，173 项通过，覆盖编排、既有执行及边契约。新增测试覆盖初始模板/默认阶段、资产阶段/别名重试、范围保留、重复编译、绑定来源、歧义拒绝。

交付修复此前通过实际部署依赖的 55 项验证及当前源码的 10 项针对性测试。完整源码专项整改仍有未部署内容，本次增量部署仅包含 `InterpretationPlanOptimizer` 及其内部类，保留已部署阶段识别、环境契约、交付绑定和日志修复；未合入其他协议及分析状态调整。

部署基线 SHA256：`4354ecfe5d64a8088bf026eb759fc306a93498715058a0487823c37948d5cb13`。

新包 SHA256：`716d636d4c1e2d0252fb4351506c5f397184f5ec2a7ad3b82bf1c605ba50234b`。

部署前确认无活动任务，保留运行包、配置及日志启动脚本备份。验证材料：`target/codex-live/staged-workflow-20261012`。联机结果须独立确认，不能以单元测试代替真实巡检交付。

## 分页契约和续跑修复

后续任务 `383ed853-bc8f-339a-8afc-4d1776c22644` 验证了固定资产→模板阶段的正常交接，但未完成取数及报告。模板发现返回 `nextCursor` 后再次取得首页，随后模型服务出现 `UnresolvedModelServerException`，任务失败。

已确认的分页根因：

- `OpsCapabilityBridgePublisher.specification` 未声明 `cursor`，虽然内部 `CommandTemplateDiscoveryService` 已实现并返回分页游标。`compileDirectToolArguments` 依据公开契约过滤了该未声明参数。修复公开 schema，沿用内部既有分页实现及页数、候选上限；Runtime 不放宽未知参数校验。
- `AnalysisRefinementCoordinator.enforceTemplateDiscoveryContinuation` 使用规划时的输入，未使用 Runtime 实际执行的身份绑定、检索规范化后输入。修复为从对应成功节点的 `resolvedInput` 恢复完整查询范围，再应用 Runtime 发出的游标。
- `InterpretationPlanRuntime.applyStepInputEnricher` 续页时再次改写检索词，破坏发布者游标绑定的查询。模板续页保留上一页实际输入，不再次进行模型检索扩展；普通工具及首次查询保留原行为。
- 系统补齐前置资产节点时不复制模板分页游标到资产阶段。

源码定向验证：编排/执行/续跑 187 项、MCP 发布/发现 54 项，共 241 项通过。部署依赖验证：API 180 项、MCP 13 项通过（反射执行普通 `@Test` 方法，未代替 Maven 参数化测试）。

增量包：API `6ed548fcd8f784f30e8583dcf7cfd83d54c6a16c98cb6ab9908b1e6c55cdd597`；MCP `06917bd289cd95bd20575a22e7dd416b58c146ca77f5305118b4eb38311b655a`。MCP 基线为 `cbca3d7c696ccb2f323b1b4364aae99bd06884d90ff22751fe6a161e4cbaa121`。两端均保留备份；配置、角色授权、模板和模型选择不变。

验证材料：`target/codex-live/pagination-fix-20261012`。真实任务最终交付尚待本次部署后的复测确认。


## 正式资产选择结果交接（继续修复）

失败任务 `6fb06e3e-4070-3c67-ac4a-613cca947985` 已选中两个授权资产，但交接代码只消费可选 `nextActions.input_changes.assetId`，忽略 Runtime 保存的 `runtimeSelectedAssetIds`，使模板阶段再次进入资产选择。

- `InterpretationPlanRuntime` 逐项核验正式选择记录，并仅从当前节点祖先链交接资产范围。保留单资产身份兼容；多资产必须具有真实返回的规范 ID。
- `OpsCapabilityBridgePublisher` 识别固定流程交接的 ID 集合，继续原有模板发现，不重新发现或选择资产。
- `CommandTemplateDiscoveryService` 在原有环境、标签和模板授权过滤上增加 ID 集合交集；空集合、未知 ID 或环境不匹配不回退到其他资产。已绑定身份不再由关键词推断改写。
- 模板批执行沿用原有受控机制，保存每个选中资产的绑定上下文，并核对模板结果中的资产范围；不默默使用首个资产。原有批量上限、模板契约、执行权限仍生效。
- 空正式选择返回 `ASSET_SELECTION_EMPTY`；缺少证据、模板范围不一致和批量超限分别明确反馈。

没有新增资产发现工具、任意取数工具、模型重新绑定入口、业务关键词判断、业务评分或发布审批。模型分析范围仍是系统流程交付的授权模板与数据。

源码相关测试：Runtime/编排/续跑 188 项、MCP 发布/发现 56 项，共 244 项通过。服务器实际依赖隔离验证：API 181 项、MCP 52 项通过。空选择保护另复跑 Runtime 158 项及部署依赖 181 项通过。

部署：MCP `9d2ff3c6e0d63c19405c621f18e9175f7ad78d1fca8cd388bb84b098d8f0d291`；API 交接修复 `9990d6fbbcc7a9ea2741472847dd5033ab06ab0bf241d0b7b20569bda1c34efd`，空范围保护版本 `85e327127f29cbb72a58b698f1674bc61e8a0401c05b16439e0e15f847acb434`。材料在 `target/codex-live/asset-handoff-20261012` 和 `target/codex-live/asset-scope-guard-20261012`。

部署时 `/opt` 满盘，API 备份失败时尚未停服或替换运行包。只清理本次调试的具名重复暂存 jar，保留运行包、回滚备份、配置和业务数据。MCP 首次启动因 RocksDB 无空间失败，释放空间后重新启动并刷新 API 工具目录（32 项）。恢复前提交的任务 `f6f10fe6-79d8-3315-ae52-0b372b8c39e8` 因 `UNAVAILABLE: io exception` 失败，不能作为代码成功验证。

完整真实巡检交付待后续联机任务确认。
### 公共过滤协议修复

任务 `121e69f6-7ae3-3ca5-89fb-152fb88480a3` 实际交接了正式资产范围，但公共过滤注册表拒绝 `assetIds`，任务未成功。这说明只测试内部过滤和桥接模拟不足以验证完整调用链。

`TargetKindRegistry` 的可行性检查、输入校验和协议元数据现在共用工具维度的允许字段定义：仅已实现集合范围交集的模板查询接受规范 `assetIds` 数组，资产发现契约不变，其他字段继续严格校验。`TemplateDiscoveryMcpToolPublisher` 同步声明模板过滤契约，业务桥公开参数结构。测试改为真实 `CommandTemplateDiscoveryService.query` 入口，并增加模板/资产阶段契约差异测试。

MCP 源码 71 项及实际服务器依赖 71 项测试通过；部署 SHA256 `6b004f06b42291ec3114abb8c67082ca1fc16e0ec535eda051b96eedeb5b0962`，API 仍为 `85e327127f29cbb72a58b698f1674bc61e8a0401c05b16439e0e15f847acb434`。材料：`target/codex-live/template-scope-contract-20261012`。真实任务成功仍需独立复测。
### 最终绑定分发检查

新增测试直接调用原有模板批编译器，验证一个授权模板能绑定两个选中资产，且兄弟分支的第三个资产不进入该批次。旧选择查询只接受至少两个模板，会漏掉一个模板对应多个资产的合法绑定；改为识别非空正式模板结果，批执行资格仍由原有数量和契约判断。

模板元数据仅来自对应分支的正式模板选择结果；资产上下文交接也使用该节点祖先范围。每个编译请求保留其规范资产 ID、名称和环境，不继承其他资产的路由身份。原有模板自身指定资产的绑定仍不能被覆盖。

Runtime/编排/续跑源码 189 项通过，实际部署依赖 182 项通过。最终 API SHA256：`b21df8dc6a5773ba8dd94cb9a0dfaccd4074e463f7d24053030cb4008ce589c4`。最终 MCP SHA256：`1d65a76a5e6ed6f451fdfcff1d24e7878562c4baf18c516dc906bffeaf17812c`，MCP 源码及部署依赖均 71 项通过。不支持资产阶段的能力不声明资产集合绑定字段。材料：`target/codex-live/asset-binding-final-20261012`、`target/codex-live/template-scope-final-20261012`。
## 最终联机复测成功（2026-10-12）

真实任务 `1a4213b0-7812-3f0d-84b3-f27b74c2bfa1` 于 18:35:44 提交，18:46:27 完成，状态 `SUCCESS`。固定流程交付 1 台授权 DEV 资产，按既有模板执行 6 次 SSH 检查，全部成功、退出码为 0：CPU、内存、磁盘、系统负载、运行时间及系统概览。没有通过人工 SSH 巡检替代 Agent 验证。

模型分析进行了 5 轮、15 次受控 `READ_TEXT` 读取，使用返回的数据句柄和分页偏移；分析阶段未调用资产发现工具或修改绑定。首次发现请求使用了未声明的 `filters.description`，执行层明确拒绝，模型根据反馈修正后继续；该异常没有被解释为无资产。最终模型声明 `COMPLETE` / `FINAL`，Runtime 当前会话记录 `DELIVERED`，实际交付文本 SHA256 与记录一致：`32ffac32340ecc9a7a0aea20d46d7f24757669bf3aea63a324002dee192a10d5`。不需要额外 `PUBLISH`，最终响应未附带执行请求。

巡检原始数据与报告核对：CPU 忙碌约 0.8%，内存使用约 64.7%，根分区使用率 71%，负载 0.15 / 0.20 / 0.30，运行时间 208 天 9 小时 43 分。原始系统概览的另一采样时刻负载不同，保留其时间差异。报告明确标注未完整读取系统概览输出的限制；不能宣称全部原始输出均已读取。报告为模型原始英文输出，未重写或替换模型判断。

验证材料 `target/codex-live/asset-binding-final-20261012` 保存原始报告、任务结果、101 条执行事件、94 条观测及验证脚本。定向源码测试共 260 项通过（Runtime/编排/续跑 189，MCP 71）；服务器实际依赖隔离验证 253 项通过（API 182，MCP 71）。后者只执行普通 `@Test`，参数化测试以源码测试结果为准。真实任务为单资产；多资产及兄弟分支隔离由针对性回归测试覆盖。

完整请求以字符实测，不是 token 实测：静态核心指令每轮 3,402 字符；L0—L3 等层内容依次为 30,755、47,491、47,695、48,010、48,694 字符；实际完整请求依次为 40,349、57,085、57,289、57,604、58,288 字符。没有相同任务的修改前完整请求基线，因此不报告长度降幅。本轮优先修复固定流程职责与协议交接，不宣称此前更广泛的提示词审查已全部部署或验证。

最终运行包指纹已再次核对与上述最终 API/MCP SHA256 一致，两个 Java 服务进程存活。只删除了与当前运行包指纹一致的本次重复暂存 API jar；运行包、回滚备份、配置及业务数据保留。`/opt` 仍接近满盘，是独立的容量风险，未扩大本次修复为业务数据清理。