# Phase 2：代码级根因矩阵（修复前提交）

基线：远端 `fix/runtime-data-exploration-delivery-20261106` / `e9eda1b9a`；实施分支 `fix/runtime-cline-phase2-20261107`。已完整阅读指定五份审计/验收文档。工作区既有 completion-review、空数据脚本及专项材料保持原样，不并入本次提交。

## 六次任务与源码核对

服务器保留的六份 journal、模型工具回执、反馈及 SDK checkpoint 用于恢复实际执行源码；原始 source 加上已安装 SDK Editor 回放原始 repair，按执行 codeHash 逐份比对。12份执行源码全部恢复且 SHA-256 一致。私有源码仅保留在忽略的 target；公开证据只含执行标识、Hash、错误行及是否包含必需输出路径，不包含提示词、工具参数正文或数据行。见[源码核对](../testing/evidence/runtime-cline-phase2-20261107/source-reconstruction.json)。此处是离线恢复原始代码，不是替模型修复或生成业务报告。

| 任务 | 原始失败点 | 代码级原因 | 分类 | 最小修复/验证方向 |
|---|---|---|---|---|
| flash-1 | v1 NameError，74行；v2 TypeError，287行；24182字符补丁被拒绝 | v1 comprehension 循环变量 nf 却返回 f；v2 浮点表达式与 Decimal 相乘；后续超长补丁超过每段16000限制 | 生成代码缺陷及模型未正确分段；未见源码传输损坏 | 复用有界输入读取器消除手写解码；保持精确编辑与原预算；分别复现三类错误，不替模型重写分析 |
| plus-1 | v1/v2 quote_code KeyError；v3 exit=0但无rows.jsonl | 用上传的 original-1.json 查 nativeFileSchemas；该文件是原始schema，绑定信息在本次 Runtime 外层schema.json，因而col_map为空；继续使用物理cN列后访问逻辑字段；v3仅诊断打印 | Schema使用错误，非DATA-08旧交付缺陷；报告生成未完成 | 提供明确以当前 manifest 和 native fileId 为参数的读取辅助函数；校验原manifest/part Hash及映射；输出要求不放宽 |
| flash-2 | 仅一次执行，exit=0但无rows.jsonl | 源码止于读取、打印字段/目标样例，没有报告输出语句 | 模型阶段程序未交付完整程序 | 缺文件反馈绑定当前程序末尾定位；继续原生修复，不自动补报告 |
| plus-2 | 仅一次执行，exit=0但无rows.jsonl | 程序止于检查producer声明，没有报告写出 | 同上 | 同上；真实回归必须有文件、引用、图表、归档及复读 |
| flash-3 | v1 SyntaxError，77行；v2 NameError，105行 | 关键字实参中未加括号的赋值表达式；修复后又引用未定义qt（实际table变量不同） | 生成代码及修复缺陷 | 保持原生Editor；技术错误反馈与精确源码锚点；复用读取器减少解码代码量 |
| plus-3 | v1/v2缺rows；v2只有两个Plotly草稿；v3 SyntaxError，224行 | 初始阶段程序未输出报告；补丁引入字符串内部未转义双引号，含rows写出语句却无法编译 | 生成代码及修复缺陷，图表草稿不等于交付 | 原生补丁+真实语法/文件校验，不借用上一次执行输出 |

错误类别分别处理：KeyError核对具体schema层；TypeError保留Decimal精度不自动转换业务值；SyntaxError修复实际源码；NameError核对程序变量而非猜业务字段；缺rows不能视为计算成功；无效补丁保持原子拒绝、Hash/版本约束，不新增编辑器或代码循环。

## 已确认的适配缺口与最小实现顺序

1. **输入读取辅助函数没有进入实际输入说明，且只支持外层parts。** `PythonDockerRuntime.createManaged`已随每个Sandbox安装`runtime_input.py`，现有`load_rows`严格检查manifest/part Hash并保留presence/null/Decimal/JSON。`CodingInputLayout`目前明确不提及该模块。拟保留`load_rows`，扩展同一模块支持按本次manifest中native fileId读取已BOUND原始Parquet；不让上传manifest中的旧fileId决定宿主路径，不选择证券、过滤、排名或报告内容，不增加工具或权限。独立Python完整性/精度/嵌套映射测试及真实文件执行后再披露可用API。
2. **缺文件没有stack，当前`repairSourceContext`直接返回null。** `ExecutionFeedbackService`已有严格缺文件反馈；Node有按实际codeHash绑定的源码锚点机制，但只在错误stack有行号时生效。拟在可修复的OUTPUT_CONTRACT/REQUIRED_OUTPUT_MISSING下提供有界程序末尾锚点，继续同一个SDK会话；不自动补输出、不把退出0当成功。
3. **实际工具披露缺少逐次安全清单。** `runtime-model.js`已转发真正request.tools，Java逐名检查allowedTools；现有模型观测主要记录调用工具而非全部披露Schema。拟增加仅含授权/披露工具名和Schema指纹的元数据事件，在最终投影后采集；不导出system/messages/arguments，执行继续由原SDK hooks及Java回执证明。
4. **B2归档对目录采集刷新与内容版本使用同一个Hash。** 现有`market_catalog_heartbeat_v1`只排除目录last_collected_at，`market_resolve_security`候选的source_refreshed_at仍参与严格Hash。即使行情文件不变也会阻止历史报告。该项单独设计与测试，不以忽略字段临时放行旧报告。

授权逻辑`5ba60b2ac`及Cline工具系统不重复改造。现网API仍为fdad65ebd3e7，Worker为2b88bcb6b271；原生Agent/Editor/Compaction/Skills/Restore保持。

## 来源版本与授权再校验方案

内容版本、采集观测时间、访问权限、服务可用性是四个独立维度。

- 新来源采用显式版本的目录候选内容Hash，只在market_resolve_security响应的候选source_refreshed_at路径上定义非内容采集时间；候选代码、名称、市场、类型、列表成员和其他字段全部仍参与版本校验。保存取得时的采集时间，不能展示为当前采集时间。
- 已有locator继续按其原Hash规则校验。旧B2不能改写原locator、response_hash或原报告。若要兼容，应从原SDK已披露的原始工具结果恢复原候选，校验其旧Hash与locator严格一致后，建立可追溯的旧版本证明，再与当前候选内容比较；原始回执缺失则继续拒绝。
- 每次访问仍调用当前Agent/用户资源授权检查；撤权继续拒绝。来源不可用与内容变化分别返回明确错误，来源不可用不冒充内容改变，也不凭旧缓存绕过授权。
- 本阶段可先实施新版本规则并保留旧严格行为，B2仍须明确未解决，直到旧版本证明和真实复读验证完成。不能仅靠新任务通过宣称历史报告已恢复。

## 修复前基线和验收

六任务完整报告0/6，既有12次执行反馈及源码Hash均保留。现有辅助读取器可复用，原生补丁拒绝有完整反馈，不允许增加预算、替换模型或手工补写报告。

每项修复独立提交、定向回归及实际执行证据。部署沿用空闲/备份/目录就绪门禁，保留旧镜像与JAR。真实回归复用相同四个文件（Parquet SHA `32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4`）、既有模型和原预算；分别验证正常图文、空数据自主回退、源码/缺文件/引用恢复、Worker恢复及预算耗尽。历史文本成功、SDK单测、单个工具成功、Python零退出均不计整体验收。

当前状态：根因矩阵已形成，实施与真实回归尚未完成；所有未知结果继续UNKNOWN。
