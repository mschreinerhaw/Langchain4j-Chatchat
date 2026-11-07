# Runtime OS / Cline 第一阶段审计与 P0 证据

日期：2026-11-07（Asia/Shanghai）。范围：本地源码、已安装 SDK、现网只读核查、既有原始任务记录及定向回归。本轮未修改业务代码、共享模型、预算、授权、数据库或部署；未提交新业务任务。审计初稿与新采集证据原存于忽略的 target 目录；经用户要求，本报告和脱敏证据独立纳入版本控制，未覆盖已有审计文档和未提交改动。

## 结论

1. **旧任务的 Java 授权缺失有证据，当前同一缺陷不能直接认定仍存在。** 旧华是科技任务 `analysis-coding-82435f72...` 合同只有六项基础工具；`CodingAnalysisService` 后续提交 `5ba60b2ac` 已扩展非文本任务资源传递。本地当前实现使用 `knowledgeTask || resourcePython` 合并资源工具。不能重复实施任务书中的旧修复。
2. **真实后续任务已打通行情工具调用。** 本次从服务器既有 B2 journal 直接提取合同，包含 market/news/web 工具；其既有工具轨迹包含 market_search、market_get_history、market_resolve_security、market_get_day_snapshot 和 Python。合同仍为 networkPolicy=NONE，仅允许 CODE_EXECUTION。这证明服务端代理与执行网络隔离可以并存。
3. **约 300 秒是可观察的调用失败时间，不是已证实的供应商内部根因。** 必须区分父 Agent 规划、Coding 回调、供应商请求、任务截止和停止对账。当前 Node 的 model 回调按合同剩余截止时间设上限，不能把通用 callbackTimeout 默认 90 秒认定为模型超时。
4. **原生能力已有较多实际复用，不能把关闭全部默认工具等同于缺失 Agent 能力。** Agent、Skills、文件读取、Editor、Compaction、restore/continue 均有原生接口接入。通用 Bash、宿主任意文件和网络没有开放的必要。
5. **整体业务验收仍不能 PASS。** 既有六轮对照完整业务报告 0/6；B2 有成功计算及初次归档，但既有复读证据显示来源重校验 403。该复读属于历史证据，本轮未重新下载该报告，也未宣称当前仍为相同状态。

## 版本与现场

- 本地分支：`fix/runtime-data-exploration-delivery-20261106`，HEAD `f0d2f8a7fa12ca60946eef2ac578abe6c05a4ef4`。
- 开始时已有两个脚本和一份审计文档修改，以及空数据专项未跟踪材料；全部保留。未创建整改分支，因为用户要求本阶段先审计、确认后才实施。
- 现网 API：`runtime-os-api:data-remediation-fdad65ebd3e7`；JAR SHA-256 `fdad65ebd3e791bd827e51fe123749faf14b811c6b0645b29337280f4ed0ff84`。服务器 deployment 回执 sourceCommit 与本地 HEAD 一致；这不是一次独立的可复现构建证明。
- 现网 Worker：`runtime-os-coding-agent:data-remediation-2b88bcb6b271`；image ID `sha256:118b02724d1236fb6a2268f9a5b07818e60669928381dca6c2145eef0b22224d`。
- 现网运行 `dist/sdk-packages.js` 验证五个包 agents/core/sdk/llms/shared 全部为 **0.0.93**。现网六个关键 dist 文件 SHA-256 与本地 dist 全部一致。
- 首次尝试读取 Worker 的 src 路径不存在；改用实际 dist 路径验证成功。镜像没有 package-lock.json；版本结论基于已加载模块校验，本地锁文件已核对版本；本轮未单独保存其 Hash。未将不存在的镜像锁文件标记通过。
- 本轮新证据：`live-runtime.json`、`live-task-proof.json`、`live-contract-and-tools.json`。未导出凭据、完整私有会话或供应商推理正文。

## 原生能力矩阵（实际锁定 0.0.93）

| 能力 | SDK/原生接口 | 当前 Runtime | 重复或缺口 | 最小处置 |
|---|---|---|---|---|
| Agent Loop | @cline/agents Agent.run/continue | runner 创建一个 Agent，工具反馈进入原会话 | 未发现第二套模型工具循环；Python 状态轮询不等于 Agent Loop | 保留原生循环 |
| Core / SDK 宿主 | Core 工具、插件、指令服务；SDK 导出编辑/压缩等接口 | 按公共 API 组合使用，没有实例化完整客户端宿主 | 并非完整桌面客户端；不能仅凭导入包宣称功能等价 | 按具体缺口接 Adapter，避免为形式重构 |
| Skills | loadAgentPluginPackages、createUserInstructionConfigService、createSkillsExecutor | 授权包隔离落盘，原生发现，原生 skills/read_files | 自定义分发和授权属于治理；没有第二套 Skill 解释器 | 保留原生发现及逐次授权 |
| Skill 资源读取 | createDefaultExecutors.readFile | manifest、realpath、符号链接及授权检查后调用原生执行器 | 仅允许 Skill 文件；/data/input 由 input_read/Sandbox 处理 | 保留边界，不能开放宿主文件 |
| 工具发现 | Core 有默认工具目录及选取接口 | tool-catalog 是按冻结合同建立的延迟 Schema 目录 | 存在自定义目录，但其目的是合同内披露，不是扫描宿主工具 | 对照真实 request.tools；不能动态扩权 |
| Compaction | SDK createContextCompactionPrepareTurn / state-aware prepareTurn | sdk-adapter 转换表示、预算与保留关键绑定 | 有自定义投影与技术记忆；未发现另建摘要模型或压缩循环 | 继续验证压缩后证据与代码绑定 |
| Editor | createEditorExecutor | 私有临时 program.py、精确锚点、基线 SHA 校验 | 原生 editor 已复用，模型不控制宿主路径 | 保留 |
| Patch | createApplyPatchExecutor、computePatchChanges 导出 | 默认 apply_patch 工具关闭；修复通过 SDK editor 和 Runtime code lifecycle | 尚无证据表明缺少独立 apply_patch 名称导致行情失败 | 仅在真实修复缺口出现时增加受控映射 |
| Bash / 搜索本机文件 | Core 默认工具支持 | Skill 工具工厂中关闭；Python 执行经 Sandbox | 是安全边界，不是金融工具授权修复对象 | 不开放系统权限 |
| Web | Core 默认 web fetch 工具支持；搜索可由宿主扩展 | 默认直连关闭；web_search/web_fetch 经 Java 授权代理 | 不需要再加一条 Node 自由网络路径 | 保留授权模型、引擎和撤权检查 |
| MCP | Core 有 createMcpTools / Manager / transport 等接口 | Node 工具回调 Java，再由受控服务访问 MCP | 与直连 SDK MCP 的宿主模式不同；治理不能被原生适配取代 | 不让 Node 直连业务库或任意 MCP |
| 会话/Checkpoint | Agent.snapshot/restore/continue | 保存 canonical messages、sessionId、调用序号及治理状态 | restore 会重置 SDK usage；Java 继续承担累计预算权威 | 保留原任务、原会话和 UNKNOWN 用量 |
| 取消/超时 | Agent.abort、signal | deadline 触发 abort；Runtime 收集执行停止证明 | SDK 普通工具 timeout 元数据并不在该版本自动强制执行 | 保留外部截止和停止对账 |
| 问答/退出工具 | ask_question / submit_and_exit | 默认关闭；报告和终态由 Runtime 合同验证 | 不能只靠模型退出宣称交付成功；交互问答映射尚非本业务根因 | 有明确产品需求后再做受控映射 |

依据：本地 node_modules 的公共 d.ts、src/runner.js、native-skills.js、sdk-adapter.js、tool-catalog.js、knowledge-tools.js，以及本轮实际运行的 SDK 定向回归。矩阵只覆盖已安装版本；未查询或推荐 SDK 升级。

## P0 根因与证据分层

| 编号 | 事实及证据 | 可得结论 | 不能得出的结论 |
|---|---|---|---|
| AUTH-OLD | `docs/testing/evidence/cline-precode-20261106/stock-final.json` 的 contractSummary，六工具不含 market/news/web；对应旧 Java 仅 knowledgeTask 合并 | 旧合同确有资源工具缺失；Prompt 无法补出未授权工具 | 未单独重放旧用户授权快照，不能证明全部缺失工具当时都应获授权 |
| AUTH-CURRENT | `CodingAnalysisService.java:492–519`；提交 `5ba60b2ac`；现网 B2 原 journal 合同和行情调用 | 原先 textResult 单一路径已扩展；有真实业务反例证明行情可用 | 尚未完成现网全部授权组合、撤权和恢复的端到端证明 |
| MODEL-WAIT | 旧任务 model #4/#5/#6 分别约 300.840/300.933/301.309 秒 CALLBACK_HTTP_REJECTED，#7 约83.209秒因截止中止；3份完成回执、4份未知/待核对用量 | Coding 调用链确有长等待；Python执行0，首次代码未观测 | HTTP拒绝不等于已确认供应商推理慢；未知usage不能记0；不能归因上层规划 |
| DELIVERY | 既有 B2 `final-report-readback-proof.json`：19项来源18项Hash匹配，一项 market_resolve_security 候选 source_refreshed_at 变化，复读403 | 历史计算/初次归档成功与后续可下载性是两个验收条件 | 不能直接删时间戳或放宽来源鉴权；也不能宣称本轮复测了403 |
| OUTPUT | 既有 `business-quality-proof.json`：六轮0完整报告；有真实代码类型错误、缺文件等回执 | 工具可用不保证有效代码和交付完成，必须按实际失败分层 | 不足以证明 SDK 丢上下文、Runtime 传坏源码或所有模型均无能力 |

本轮没有发现足以直接实施的新 Java 授权补丁。需要先用当前失败任务的原始合同确认是否仍丢工具，避免覆盖已有修复。

## 两条模型调用链与时间口径

**上层 Agent：** 正常业务入口 → Agent 规划/派发 → CodingAnalysisService 建合同。需独立记录用户提交时间、规划请求、派发及合同创建；本轮没有取得同一当前失败任务的完整上层事件，耗时记 UNKNOWN。

**Coding Agent：** Node Agent → SDK prepareTurn → createRuntimeModel → `/internal/coding/cline/model` → Java ClineRuntimeModelBridge → 选定模型 → 结果回 SDK → 工具/代码 → Sandbox → 报告验证。

- ModelsConfig 默认单请求300秒只是默认值，不能推断实际模型配置就是该值。
- server.js 模型请求使用合同剩余截止时间；非模型回调使用通用 callbackTimeout 与剩余时间较小者。
- ClineRuntimeModelBridge 使用 Coding 剩余 deadline，并由 ToolExecutionModels.independent 处理继承的截止、取消和单次上限。两条链的事件必须分别归属，不能笼统“加长 Agent 超时”。
- 非流式 Node callback 首事件不是供应商 TTFT；供应商排队、首Token、生成内部耗时不可推测。

| 必需指标 | 本轮证据状态 |
|---|---|
| 用户提交→Coding合同创建 | 当前同任务上层链缺失，UNKNOWN |
| 合同创建→首次模型请求 | 具备观测字段；本轮未形成当前任务完整关联，UNKNOWN |
| 首次模型请求→首次有效代码 | 旧失败样本未产生代码，未观测；不能填0 |
| 首次代码→首次Python | 旧样本无代码/无Python，不适用；B2已实际执行但未在本轮重算耗时 |
| 首次Python→有效结果 | B2有回执；未在本轮重算耗时 |
| 有效结果→正式归档 | B2有历史归档证明；后续复读问题单列，不冒充全程PASS |

## 本轮验证

执行 `node --test test/tool-catalog.test.js test/knowledge-tools.test.js test/native-skills.test.js test/sdk-native-capabilities.test.js test/runtime-model.test.js`：**49通过、0失败、0跳过**，约31.1秒。

覆盖：实际安装SDK的Skills发现/读取/撤权/隔离；原生Agent工具反馈；延迟披露不扩权；restore重置usage特性；Compaction后Skill再次加载；Abort传递；普通工具timeout元数据不强制执行；SDK重试hooks特性。模型与业务工具使用受控fixture，**不属于真实供应商验收**。

磁盘已有 Java CodingAnalysisServiceTest 的62通过报告；本轮未重跑，不能计入本轮新增验证。未新增或修改测试。Java→Node真实合同一致性测试已有源码用例及桥接路径，但本轮未完成跨语言全组合重跑；原始request.tools逐次快照也未在本次读取的归档中取得。

## 确认后的最小下一阶段

1. 先选定一个当前失败任务（沿用正常业务入口及原模型/预算），读取完整 CodingTaskContract、实际 SDK 每次 request.tools、上层派发事件、模型回执、工具结果与SDK checkpoint。对同一任务比较授权集、注册集、披露集、实际调用集；延迟披露应为授权子集，不能要求每轮全部工具都强制发送。
2. 仅当确认当前 Java 合同仍丢已授权工具时，沿 `AgentResourceScopeService → CodingKnowledgeService.allowedTools → CodingAnalysisService → ClineToolProfiles → Node` 定位最小修复点，在独立分支补跨语言合同组合回归；不能再次机械改旧 if。
3. 超时单独按父Agent和Coding调用链取证，定位 HTTP/模型/截止的具体失败层；保留 UNKNOWN 和原任务恢复，不改共享模型和预算。
4. 对来源刷新导致报告复读失败，先明确证据版本与实时授权复核的语义，再提出具体补丁。对代码/报告失败按回执修复 Adapter 缺口，不能代写业务结果或放宽文件和引用验证。

本阶段审计完成；**整改实施未开始，最终业务验收未通过**。用户确认下一阶段后才实施业务代码修改或部署。

## 提交材料

本轮脱敏证据见 [证据目录](../testing/evidence/runtime-cline-phase1-20261107/)。SHA256SUMS.txt 校验四份 JSON 与本报告；不包含服务器私有文件内容。
