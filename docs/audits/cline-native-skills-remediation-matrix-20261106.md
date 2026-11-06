# Cline Native Skills 后续整改：根因与修改矩阵

审查日期：2026-11-06。源码基线：`refactor/cline-native-skills-20261106` / `edc637eb4`。本文件为第一批审查交付，先提交根因和文件级边界，再分别提交 P0、P1；不将历史测试当成本次验证，也不将待验证风险写成已确认根因。

## 已确认结论与任务书差异

1. 原生 Skills 已接入，当前问题没有证据要求重新实现发现器、加载器、Agent Loop 或整体升级 SDK。
2. 正式探索 visual-0 已有 `visualReport.status=PARTIAL`，不是完全没有部分交付协议。缺口在于图表失败仍被 `artifactValidationStatus=PASSED` 概括，回调仅拦截 FAILED，技术 COMPLETED 因而不证明图表交付完整。
3. 正式探索 visual-1 的后端确实返回 BUDGET_EXCEEDED；前端状态函数漏掉此枚举，落到“等待分析”。这是代码可直接复核的缺陷。
4. 旧成果保留包含历史产品需求：正在执行的子节点可显示祖先成果，并有“画布保留”提示。不能直接删除全部祖先回显；应在当前节点失败且无结果时停止回显其他节点，保留当前任务自己的已验证阶段成果。
5. 文本入口 R2 图片缺失已由归档回执确认；但公开证据没有保存完整生成代码，不能断言每次缺失的具体写盘语句。源码确认仅扫描已停止执行的 results 清单，不应扩大成扫描宿主目录。

## 端到端链路与边界

| 阶段 | 实际入口、输出和治理 |
|---|---|
| 正式探索 | `ExplorationWorkspaceController.execute:107–124` → `AssetAnalysisController.analyzeVisual`；owner 来自认证，nodeId 是幂等 requestId；输入是 owner 可读文件 |
| 文本文件分析 | `AssetAnalysisController.analyze:37–100`；同一 Java Coding 路径，visual 属性与正式探索不同；不能以文本结果代替 visualReport 清单 |
| Coding 合约 | `CodingAnalysisService.run:244–517`；任务 scope、输入快照、预算、工具授权和 reportMode；模型自主选择工具/Skills |
| Skills | `native-skills.js:9–163`；任务私有目录，官方发现/config service/executor，动态包读取前重查授权和 hash；不扫描共享 HOME |
| Python | `ClinePythonCallbackController` → 现有 Python 执行服务；输出 `/workspace/output/results/rows.jsonl` 和图表 basename；执行回执必须证明停止 |
| 归档 | `CodingEvidenceCollector.collect:184–211,311–320`；manifest/hash/stop proof 校验后，从 results 接纳文件，按 owner/task/operation 保存；不接纳模型任意路径 |
| 图表接纳 | `VisualReportArtifacts.collect:60–95,125–174`；PNG/SVG/Plotly 校验后返回 fileId、size、SHA-256；报告 evidence 关联原 result/code/receipt，失败保留原因 |
| API 结果 | `CodingAnalysisService:575–633` 投影任务状态与已归档报告；`ExplorationWorkspaceController.finish:151–165` 持久化 receipt |
| 页面 | `explorationWorkspace.js:24–43` 解释状态与画布归属；`DataExplorationView.js:54–63,243–251` 绑定节点并轮询；图表通过既有 owner 认证 artifact API 展示/下载 |

## P0 修改矩阵

| 编号 / 证据等级 | 根因与源码定位（基线行号） | 最小修改文件与方案 | 为何不是另做 SDK 功能 | 验证与回滚 |
|---|---|---|---|---|
| P0-01A 已确认 | `VisualReportArtifacts:66,258–263` 拒绝 results 之外文件、带样式 span 等；`VisualReportMode:38–42` 未明确普通标题与 font 的安全组合；历史两文本任务零图归档、正式探索五图拒绝 | `VisualReportMode.java`、自有 `runtime-report-delivery/SKILL.md`：补充安全 Plotly 标题/字体示例，明确 results 输出和实际引用；不修改第三方 Skill，不强制读取 | SDK 不掌握 Runtime results/安全契约；在现有合约和自有 Skill 内表达，不新增控制层 | 保留 span/script/url 拒绝；安全 line/bar JSON 通过；真实模型至少两类图、逐文件 hash、owner 下载验证；可单独 revert |
| P0-01B 已确认 | `VisualReportArtifacts:165` 有被拒图仍标记 artifactValidationStatus PASSED；Python 回调 `:297–304`、完成回调 `:622–626` 只拦截 FAILED；模型得到的技术修复信号不完整 | `VisualReportArtifacts.java`、两回调及既有测试：区分部分图表失败与数值/业务限制，在已存在回执中传递图表拒绝与可用图数，复用 SDK tool feedback；保留有效文本和 PARTIAL | SDK 无法替 Runtime 判断已归档图表是否合法；只补准确证据反馈，不让 Java 选择分析方法 | 有效/缺失/恶意图、检查 UNRESOLVED、纯文本任务分别覆盖；避免把业务证据不足当技术故障无限重跑 |
| P0-01C 已确认症状，具体生成原因待补 | `report-artifacts-r2*.json` 中 Markdown 引用 `charts/*.png`，已归档图为零；`CodingEvidenceCollector:194–205` 已复用正式归档，非没有存储能力 | 先补 results 输出契约及缺失图片回归；只对已验证 manifest 内图表建立正式引用；文本模式如何呈现图表需核对现有 row schema/入口，不直接更改全部文本任务为 visual | 不新增存储、不扫描任意路径、不改输入/owner 权限；复用 RuntimeAssetArchive 和现有 visualReport | 无归档文件的 Markdown 不得算完整交付；路径穿越、跨任务引用拒绝；真实文本和正式探索入口各验证 |
| P0-02A 已确认 | `explorationNodeState:25–34` 漏 BUDGET_EXCEEDED 和显式 COMPLETED；`explorationCanvasNode:37–43` 对失败节点也回退祖先/其他分支 | `explorationWorkspace.js`、其测试、`DataExplorationView` 既有组件测试：补现有枚举映射；失败无结果时绑定当前节点，保留运行中祖先回显和同任务阶段成果 | 纯现有协议展示修复；不定义第二套后端状态，不凭 resultAvailable 猜测终态 | 成功/失败/预算/取消/部分交付，刷新还原、旧分支不串、取消阶段成果保留 |
| P0-02B 已确认覆盖缺口 | `CodingAnalysisService:575–610` 返回 status/reasonCode，但没有统一 terminal 字段；workspace status `:138–140` 只轮询绑定 Agent 的任务，历史直接文件分析不具备同一路径 | 先补准确 terminal 投影和测试；直接文件分析中断后的只读恢复需复用 owner/scope 的现有任务读取，另一个小提交处理，不通过重复 execute 当轮询 | Runtime 拥有任务事实；SDK/前端不能判断执行器是否停止；不扩大 scope，不伪造终态 | HTTP 中断、Worker 退出、停止待确认、预算超限后 API/持久化/UI 对照；无 stop proof 仍保持待协调 |

## P1 调查与修改矩阵

| 编号 / 证据等级 | 已知事实、疑点 | 文件范围与最小方案 | 验收条件 |
|---|---|---|---|
| P1-01A 已确认性能回退，原因尚不能单一归因 | 同十任务 calls 41→56，已知 tokens 875393→1286227；首提案下降不等于成本优化。正式探索前五轮含 300 秒无完整用量请求 | 复用 `runtime-model.js`、`coding-progress.js`、Java 模型桥现有事件；在现有 benchmark 脚本拆分准备/回调等待/工具/首代码/执行/归档；只有 Provider 给出数据才拆首 token/生成，缺测 UNKNOWN | 固定六类数据、模型、预算配对；逐任务+P50/P90，独立统计失败和未知用量；保留原始请求身份，不重放未决调用 |
| P1-01B 成本风险，未确认主因 | `native-skills.js:149–153` configuredSkills 序列化进 schema；15 项约 4657 字节，已有 138 项发现测试，但未证明大量元数据下的每轮实际成本 | 先测原生 schema、投影后 schema 字节/估算 token、实际请求字节；仅发现重复后做局部去重；复用 PrepareTurn/Compaction，不裁剪授权目录、不限制读取数量 | 15/138/更大目录测量；初始正文为零、完整目录可见、独立命名空间仍可用；SDK 版本不变 |
| P1-01C 已确认无效读取 | R2 与探索存在 read_files 误读 Sandbox；既有范围说明未完全消除 | `native-skills.js` 原生工具包装处补精确 scope 拒绝诊断和计数，不读取未授权文件；沿用原生反馈和当前 input_read/python 工具 | 误读与撤权/内容改变分开统计；错误不泄露宿主路径，正常参考与正文读取不退化 |
| P1-02 覆盖不足 | 简单 CSV 跳过、多 Skill、真实压缩后重读已有历史证据；Parquet 正向自主选择、同会话恢复的业务成功证据不足 | 复用 `native-skills.test.js`、`runner` 现有恢复用例与真实 benchmark；补发现身份/成功加载事件/会话 ID 对照，不在 Java/Node 写业务 Skill 顺序 | 动态撤权立即生效、同名并存、无关正文不进入上下文；真实 Provider 和 fixture 分列，不强迫任务读 Skill |
| P1-03 覆盖不足，不预设 SDK 缺陷 | `runner.js:137–205` 已恢复 modelOrdinal、sdkSessionId、执行记忆与检查点；不能因代码复杂就重写恢复 | 在现有 runner/SDK adapter/Java budget 测试中补超时、Worker 重启、compaction、动态 Skill、取消的组合验证；只有复现失败才修改对应边界 | Python 未重执行、未知 usage 未结算为零、取消后未发布、恢复同 session、预算守恒；SDK 缺陷单独最小复现 |

## SDK 复用与冗余处理矩阵

| 对象 | 分类 / 处置 | 删除或修改条件 |
|---|---|---|
| 官方 discovery/config service/skills executor/readFile | 直接复用，保持 | 不增加第二套实现 |
| `skill-distribution.js` 与 Java `CodingSkillReader` | Runtime 安全治理，保留 | 分发、授权、来源/hash 不属于模型 Skill 选择；先证实无调用再删 |
| `native-skills.js` 授权包装 | Runtime 安全治理，保留 | 不因性能去掉逐次动态授权或真实路径验证 |
| configuredSkills 参数说明投影 | SDK 最小兼容适配，保留并测成本 | 官方工具公开用途元数据、实际验证可替代时删除；当前已安装版本不足以据此升级 |
| `runner.js` / `sdk-adapter.js` | 复用原生 loop、PrepareTurn、Compaction、Checkpoint；Runtime 保留执行治理 | 不复制 SDK 内部源码；无复现不重构 |
| 工具白名单中的旧 skill_read 字符串 | 旧协议兼容候选，P2 后置 | 先核对历史检查点兼容与真实调用，不把字符串出现误判为已恢复旧 loader |

## 分批提交与门禁

1. **本提交**：只包含根因/修改矩阵；没有声称修复、运行新模型或部署。
2. **P0 状态批次**：现有枚举/结果归属/terminal 投影，前端回归与 Java receipt 测试。
3. **P0 报告批次**：安全输出契约、已归档图表准确反馈与缺图保护，既有 Java/Node/Docker/前端报告测试。
4. **P1 测量批次**：分阶段与大目录 schema 成本证据，先计量再做有数据支持的局部优化。
5. **P1 能力/恢复批次**：真实 Provider 六类配对与恢复/授权验证；不足的环境条件如实列为未验证，不编造成通过。

每批写入 `docs/testing/` 的通过/失败/跳过与版本绑定；没有失败复现或可量化依据的“优化”不进入生产代码。生产切流不在本次源码审查授权范围内，部署工件与回滚方案准备完成后另行确认。

## 证据索引

- [原生 Skills 验收与 R1/R2/R3/R4 边界](../testing/cline-native-skills-acceptance.md)
- [正式探索图表质量证据](../testing/evidence/cline-native-skills-20261106/visual-quality-exploration.json)
- [文本 visual-0 归档缺口](../testing/evidence/cline-native-skills-20261106/report-artifacts-r2.json)
- [文本 visual-1 归档缺口](../testing/evidence/cline-native-skills-20261106/report-artifacts-r2-visual1.json)
- [正式探索浏览器失败记录](../testing/evidence/cline-native-skills-20261106/browser-exploration.json)
- [SDK 原生 API 已有调查](cline-native-skills-api-review.md)（历史结论；本次没有以联网查询宣称新的 SDK 版本能力）

## 首批落地与复核修正

- 根因矩阵独立提交 `fe4b4a199`；状态修复 `e33b3e5c8`；报告契约/反馈 `fe47cf494`；P1 计量/读取诊断 `b17104b87`。修改和验证范围见[首批验证记录](../testing/cline-native-skills-remediation-20261106.md)。
- 复核后明确：`artifactValidationStatus=PASSED` 表示报告清单已通过接纳，不应直接改写为图表全部可用。新增的 `chartValidationStatus`、准确可用图数和 presentationDiagnostics **只在 read-artifact 回执中投影**，不改变历史 CODING_VISUAL_REPORT 内容与哈希。缺图报告仍保留既有 `status=PARTIAL`，没有修改 SDK Loop 或强制重试次数。
- 取消后祖先回显是既有回归明确要求的行为，继续保留其历史来源提示；本次限制的是失败或明确完成但无结果的节点借用其他节点成果。原有测试断言全部保留。
- Schema 测量为 15/138/515 项完整发现、正文零加载，工具 Schema 8835/39637/135395 字节。不能由此断言历史调用 token 增长完全来自元数据，尚未做相同 Provider 的配对实验。
- **未关闭**：文本入口实际图片归档/引用修复、直接文件分析 HTTP 中断后的只读状态恢复、真实模型两类图表与正式入口验收、P1 六类任务性能配对和恢复组合验证。当前提交不是生产可用性全部验收通过。
