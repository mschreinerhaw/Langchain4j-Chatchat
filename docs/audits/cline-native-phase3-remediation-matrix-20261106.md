# Phase 3 根因与修改矩阵

日期：2026-11-06，Asia/Shanghai。起点 `a52f12972d2eb704df246b02c3179cb7e9180443`，分支 `refactor/cline-native-skills-20261106`。已检查前八个本地提交，fetch 后工作分支 ahead 8、behind 0，工作区干净。保留全部 Phase 2 成果与失败记录，不 reset、不覆盖提交、不合并 main 或推送。

## 原始证据与不可替代的验收门槛

Phase 2 `recovery-plus-r3`：task `analysis-coding-e3fc86882a3df897fcb7d385712fb2efcbf42bffef3c33ba5c0f12e4f9700ecb`，SDK checkpointOrdinal=4、modelOrdinal=2；Worker SIGKILL 时 model-3 已有 start intent，Python 已停止并成功。模型回执随后落库，Journal sequence 50/51 记录 stop proof/未知用量保留，52 为 “Verified Coding execution recovered after worker session loss”。最近 SDK checkpoint 未前进，Node 观测仍 NEW_SDK/RUNNING。因此上轮明确 FAIL，不改写历史状态。

本轮 PASS 必须来自真实 Worker 进程中断：同 taskId、同 SDK sessionId、原生 `Agent.restore` + `Agent.continue` 的实际事件、恢复前后 checkpoint 引用/hash/单调序号、连续模型操作序号、Python 操作 ID 与源码父 hash、原预算/截止时间/UNKNOWN、权威终态和正式归档。重新 submit、新任务成功、仅从已验证结果收束、Mock/fixture 均不能替代这个门槛。

## 根因与最小范围

行号以起点源码为准，后续提交可能移动。

| 编号 | 复现与具体源码位置 | 根因 | 可复用能力与最小修改 | 风险与验收 |
|---|---|---|---|---|
| P0-01a | 在 Python 成功后的模型调用期间终止 Worker。`ClineCodingAdapter.java:188–214`；`DurableCodingTaskService.java:143–178`、`:209–264` | 最近 settled checkpoint 只覆盖 model-2；真实 model-3 回执落库后 operationCount 不匹配。仅支持“本地结束且无响应”的附加模型操作，不能把已存回执交回 SDK，最终退到归档收束 | 保留原 SDK canonical messages；在现有 Journal 只记录模型操作与其原始 checkpoint 的治理性绑定，不保存第二份会话。只允许已停止且有不可变回执的那个模型操作，从已绑定 checkpoint 经官方 restore/continue 在原会话消费原回执；Provider 不再 dispatch，不增加计费/执行。原始 operationCount/未知执行校验继续严格 | 不允许泛化为跳过 operationCount，也不把未决 CODE/TOOL 当可重试。先做请求/回执/检查点绑定、重复结算、取消/预算/旧 fence/损坏测试，再做真实 Worker 中断；实际技术实现需以 SDK 行为验证，不预宣布可恢复 |
| P0-01b | `runner.js:802–843`、`:884–1001`、`:1015–1060`、`:1127`；`runtime-model.js:2–24`；`ClineModelCallbackController.java:398–484` | 原 SDK restore 已存在，但 beforeModel 会再次保存旧 ordinal；原生 continue 重新构造请求，模型回执边界与最新 SDK snapshot 尚未对齐。回执缓存已有幂等返回，缺少原 checkpoint 的准确准入绑定及可审计消费事件 | 复用 SDK hooks、现有模型桥与 immutable receipt；恢复消费阶段不提前覆盖旧 checkpoint，消费后由 SDK 产生 canonical tool/message events，再正常保存下一 checkpoint；区分缓存回执消费与新推理观测 | 恢复前后 stable session/code/compaction/授权资源；不改 SDK 源码、不增加 loop、不把 Java 变成 assistant message 生成器。真实前代码/Python 后/修复中/compaction/Skill 后断点分别记录 |
| P0-01c | `ClineCodingAdapter.java:135–185`；`runner.js:1048–1059`；`native-skills.js:14–57` | fencing、取消、Python reconciliation、任务隔离 Skills 重建已有；不能因新恢复路径绕开 | 复用现有 lease/recoveryFence、stop proof、authorized resource distribution 和动态资源再授权；checkpoint 不可用或不匹配时明确拒绝，不静默重新 submit | 未知调用不记 0；未决 Python 先核实；撤销 Skill/旧 fence/预算耗尽/损坏 checkpoint 负向测试；实际 live 故障结果单列 |
| P0-02 | `monitor.ts:79–106`、`:133–166`、`:263–281`、`:355–400`；`server.js:186–191`；`ClinePythonCallbackController.java:73–124` | Monitor 是 observation-only；重启只改内存为 INTERRUPTED，持久文件可能仍 RUNNING；同 fence start 覆盖旧观测；现有 GET list/detail 没有融合 Java 权威终态；finish 没有权威终态单调边界 | 优先扩展既有 READ-only monitor-python 的状态投影，复用签名回调与 task/owner scope；Monitor 从权威持久态确认终态/原因/结束时间/版本，保留原事件与诊断，旧进度不可覆盖终态；恢复同任务保留先前观测。查询同步开销只在 Monitor 读侧，失败显示失联/待核实 | P0-01 单独提交后再实施本项；Node/Java/Monitor API/浏览器/实际重启/迟到事件必须指向同任务，不靠清空缓存或修改历史任务过关 |
| P1-01 | Phase 2 quality-independent.json；现有报告 Skills 与 Python 输出检查 | baseline 日期星期/部分周解释错误；candidate 周起止表达不清、客户行数文字矛盾。归档安全通过不等于内容正确 | 优先现有分析/报告 Skill 的确定性复算、日期/Decimal/金融单位/图文一致检查与示例；保留模型自主结构/方法/图型，不新增审核 Agent，不重做归档 | 固定六类：销售、跨月 ISO 周、关联、空值 Decimal、持仓盈亏、多图趋势；原文件 hash、独立期望、原报告/图与实际渲染复核全部保留。失败不挑样本重跑 |
| P1-02 | 现有 benchmark/collect 脚本及 Phase 2 配对证据 | 原 29.2% 是已知 Token 小样本观察，不是计费或恢复收益；正常与故障混算会失真 | 只复测受 P0/质量修复影响的固定任务，原预算/模型；正常与故障分别统计首次代码/Python/结果/交付、模型/UNKNOWN、checkpoint/恢复、Python 次数及质量 | qwen3.7-plus 与 flash 独立；Provider 计费/版本未知保留 UNKNOWN；没有改善证据不宣布性能优化 |

## 发布与保护

现有验证服务器/应用鉴权与部署授权继续有效，不写入本文。Phase 2 已部署 API JAR `b497bbe4cf443259248c04546d21d7fe4f6c774b123c47a5404cfe54f66c47bd`，Worker 镜像 `sha256:4a72ae673cb09301e66b404fbc2cf4436550b0aeca3828aa031f6c7b571cb8b2`。API/Worker 原容器及 `api-r4/backup`、`worker-r4/backup` 不清理，实际发布前重新核对静止状态、配置/挂载、安全参数及回滚工件。

先提交本矩阵，再按 P0-01、P0-02、P1-01、P1-02 顺序实施。SDK 固定 0.0.93；不修改共享模型/预算、不新增 SDK Loader、Agent Loop、Transcript/Checkpoint 存储、审核 Agent 或数据库迁移。未测试项必须 NOT_TESTED，真实失败必须 FAIL。
