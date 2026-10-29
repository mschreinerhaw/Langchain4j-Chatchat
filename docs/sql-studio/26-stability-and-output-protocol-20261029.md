# SQL Studio 稳定性与输出协议整改（2026-10-29）

本轮基于上一轮未提交的工作区修改继续实施，保留既有工程、授权、编辑器和模板链路。以下区分源码缺陷、真实请求结果与尚不能复原的现场问题。生产没有替换；验收使用构建后的真实 Vue 页面，连接现场隔离 API / MCP / Cline 和已配置模型，没有拦截接口或模拟进度。

## 1. 问题定位

### 输出解析与任务意图不一致

- 原 `SqlStudioMetadataAssistant.finish → decode` 把所有最终回答作为 JSON 对象读取。错误文案 `Return one valid JSON output object` 来自该类的严格解析器；捕获后统一记录 `OUTPUT_VALIDATION_FAILED`，无法区分格式、截断、字段证据或版本错误。原提示词还以单引号和未加引号的键示范“JSON”，这是格式不稳定的诱因，**不能据此认定找不到 ID 的原请求具体返回了什么**。
- 真实解释请求证明另一处独立断点：`SqlStudioService.prepare` 的解释操作走原有无工具 `llm_chat`，不走 Cline；模型返回 Markdown 后，`SqlStudioService.result` 仍要求文件 JSON。旧请求 `e28b1af4-78a4-4e32-9c41-05afe149da63` 的受控回执为 Markdown 与 JSON 解析错误。本轮修复实际解释入口，没有把解释迁入 Coding Agent。
- 原响应回执只有配置和总耗时，没有结束原因、Token 数、最终文本预览或细分校验原因，无法从通用错误倒推原始模型内容。
- 前端 Markdown SQL 卡片原先仅依赖编辑器可写性启用插入，即便该代码块只出现在未经字段校验的说明文字里。现在插入能力还必须对应后端接受的提案，错误输出与未校验的说明不能成为自动写入入口。

主要位置：`SqlStudioMetadataAssistant.java` 的 `Turn.execute / finish / review`；`SqlStudioService.java` 的 `prepare / result`；`SqlOutputBundle.java`；Vue `SqlAssistantPanel` 与 `sqlIdeState`。

当前源码定位（后续编辑行号可能变化）：

| 文件 | 行及职责 |
| --- | --- |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioMetadataAssistant.java` | 262 最终答案校验入口；334 诊断投影；361 原错误文案所在的内部 JSON 读取器。 |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlMetadataOutput.java` | 16 最终格式归一化与明确失败分类。 |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java` | 581 解释结果进入只读适配，保持原执行路径。 |
| `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlOutputBundle.java` | 13 解释文本适配，不接受文件修改。 |
| `chatchat-api/web-app/src/js/components/sql-studio/SqlEditorPane.js` | 143 当前文件精确监听，替代整份文件集合深度监听。 |
| `chatchat-api/web-app/src/js/components/sql-studio/SqlSchemaExplorer.js` | 144 初始化与折叠时的目录加载边界。 |
| `chatchat-api/web-app/src/js/views/SqlStudioView.js` | 97 单一拖拽处理、实际尺寸起点与监听释放。 |
| `chatchat-api/web-app/src/styles/pages/sql-studio-ide.css` | 55 真实 Grid 轨道尺寸分配。 |

### 反复激活与布局

- `SqlEditorPane.js` 原来深度监听整个 `props.files`。每次输入会修改文件草稿并再次调用 `activate`、更新选项、安排 `layout`，而编辑器本身还启用了自动布局。
- 改为只监听当前文件与预览的必要字段；当前模型、源码和只读状态一致时直接返回。单一 ResizeObserver 将尺寸变化合并到一帧，跳过零尺寸和未变化尺寸，卸载时断开并取消待执行帧。
- 浏览器追加测试在 150% 等效布局发现分栏宽度往返后首个可见行偏移一行。实际 editor API 显示高度445px、内容高度1218px时，滚动位置由773变为761，差值恰为12px横向滚动条高度。Monaco 的内容高度会随着横向滚动条显隐变化，扩宽时原底部位置被钳制，恢复宽度时只恢复普通视图状态不会重新回到底部。现在区分底部锚定与普通首行锚点：前者跟随新的内容底部，后者复用编辑器视图状态和行偏移，不重建模型或编辑器。
- Monaco 的 `setModel` 本来会移除并重建内部模型视图 DOM。库源码 `codeEditorWidget.js` 的 `_attachModel / _detachModel` 可证实。因此验收核对真实 `editor.getId()` 与宿主节点，而不是把内部 `.monaco-editor` 视图变化误判为编辑器实例重建。
- 分隔器原来依赖相邻节点选择器定位，改为明确的左右分隔器类和 Grid 列。折叠时原 `v-if` 移除分隔器，面板及拖拽轨道宽度都为 0；没有添加透明遮罩、额外隐藏层或宽高动画。

### 数据源导航提前加载

`SqlSchemaExplorer.initialize / chooseAsset / chooseNamespace` 原来直接取表目录，默认展开。现在首次挂载默认折叠，只有主动展开才加载目录；已展开的目录收起保留，重新展开复用。数据源变更、权限或快照失效仍按原机制清除过期结果。AI 上下文选择器所需的授权源、命名空间及 `pageSize=1` 权限探测保留，不能把这些安全请求误删为“重复请求”。导航展开状态不写入本地持久化；首次进入固定折叠，项目资源树和原面板布局偏好保持原行为。

## 2. 最终调用链与协议

```text
数据库探索 / 据证据生成 SQL
  Vue → 原 generation API → Runtime 任务 → SqlStudioMetadataAssistant
  → 现有模型解析器 / ChatModel.chat
  → 工具请求：仅 sql_schema_context_query，沿用授权和快照机制
  → 最终回答：SqlMetadataOutput → 原字段、SQLGlot、业务口径校验
  → 原检查点 / generation result → 展示 / 用户明确插入 / 原差异审查

SQL 解释
  Vue → 原 llm_chat 入口 → Markdown 或合法解释 JSON
  → SqlOutputBundle.explanation → notes + files:[] → 只读展示

工程生成 / 修复 / 优化
  原 SQL_WORKSPACE_V2 → 原 Cline workspace_read / workspace_propose
  → 原文件契约、SQLGlot、审查与版本保存
```

最终输出处理规则：

| 响应 | 行为 |
| --- | --- |
| 纯文本解释 | 直接作为说明，文件为空，不要求额外模型调用转换 JSON。 |
| 纯 SQL、完整 Markdown SQL | 数据库助手适配为 SQL 提案，再走原字段、方言和业务口径检查；并非直接接受为可执行成果。 |
| 文件 JSON | 验证 `notes / files / candidateTables` 类型、版本和原文件契约，转义与换行交给 JSON 解析器处理。 |
| 单 SQL JSON | 支持声明的 `sql / explanation / executionStatus` 形式；禁止与 files 冲突，禁止模型宣称已执行。 |
| 非法或截断 JSON | 记录 `OUTPUT_JSON_INVALID / OUTPUT_JSON_TRUNCATED`，不当成普通 SQL 写入。 |
| 输出 Token 上限 | 依据 SDK `LENGTH` 记录 `OUTPUT_TOKEN_LIMIT`，不接受为完整结果。 |
| 字段或候选表不符合证据 | 记录 `OUTPUT_EVIDENCE_VALIDATION_FAILED`，与 JSON 格式错误分开。 |
| 工具调用 | 在最终答案解析前分流，不把工具响应当作最终结果验证。 |

格式／可恢复校验修复最多两次，继续使用已交付证据；原 12 次模型调用和 24 次工具预算以及任务剩余截止时间保持不变。SDK 内部网络重试与这两次输出修复不是同一种计数。

数据库助手使用同步 SDK `ChatResponse`；前端轮询的是任务事件，不是另一套模型流式拼接。Cline 的原生流式工具参数组装保持原链路。SDK 工厂、DashScope 原生与 Runtime 流式客户端均执行既有回归；没有强制所有供应商使用可能不兼容的 `response_format`。

## 3. 运行诊断

诊断复用现有 Runtime 检查点 `diagnostics:<generationId>`，由原 generation 结果接口在项目所有权及当前元数据授权下返回，不增加数据库或独立监控服务。

高级设置中的“查看本次运行诊断”打开现有底部面板，展示：

- 当前尝试总耗时、授权核查、工具耗时、输出校验及其中的解析耗时。
- 每次模型调用的独立耗时、配置模型／协议、SDK 结束原因、输入／输出 Token。
- 响应类别、长度、经过脱敏且最多 8000 字符的预览、校验原因和修复序号。

普通对话保留业务阶段与结果，移除原始结果 JSON 的堆叠。旧通用 JSON 错误转换为可理解的失败说明，状态仍依据后端事实；修复中的前序失败不会停止加载。任务计时优先使用当前任务开始时间，不把前序任务累计时间当作单次模型耗时。

预览对凭据、Bearer、私钥、邮箱、长数字及 SQL 字面量做限制性脱敏。这里是受控预览，不是完整原始报文归档。SDK 未提供的字段显示“未提供”；原始 HTTP Content-Type 没有从 SDK 暴露，不伪造网络层响应头。历史任务没有新版诊断时明确说明缺失。

## 4. 真实模型与页面验收

全部使用正常登录、真实 Vue 路由和现场授权服务。

| 场景 | 真实结果 |
| --- | --- |
| 元数据问答 | `90e7d4cb-c887-44ef-82d3-cf47daa1a099`，SUCCESS，9 次模型调用、12 次工具选择，98.183 秒，真实候选字段可查看。 |
| 基于证据生成 SQL | `e519960a-236c-481e-b49a-6372ec20aad7`，SUCCESS，1 次模型调用、0 次工具选择、复用12份证据，18.608秒；高亮、复制、插入／替换／撤销通过，未自动保存或执行。 |
| 用户补充的原始问题 | `5348be4b-f81d-420e-b020-a9006fee3517`，SUCCESS，files=[]。受控授权库缺少支持维持担保比例的已确认字段／业务口径，返回缺口而非虚构计算脚本。 |
| SQL 解释 | `6392ed30-d58d-4cef-9719-49a2c410e3f0`，SUCCESS，Markdown 正常展示，outputError 为空，files=[]。保持原无工具入口。 |
| SQL 优化 | `8d0b7ddd-4123-4e6f-b6eb-7ff6fa9b5519`，SUCCESS，真实 Cline 返回1个文件提案，SQLGlot 解析通过，`FIELDS_NOT_EVALUATED; SQL_NOT_EXECUTED`。这是受控常量 SQL，不冒充真实业务数据库执行。 |
| 真实停止 | `b2bbf76a-7f52-4909-ae47-5e56555083e0`，CANCELLED，停止加载；保留重试入口。 |
| 刷新／重启后恢复 | 最终隔离 API 版本再次恢复维持担保比例任务，新增 generation 请求0次，原诊断与结果保留。 |

上述用户原文为“请基于上述已确认的元数据生成 SQL。统计口径与筛选条件：编写维持担保比例计算脚本”。原任务 ID 已无法提供，因此此次使用当前授权的 `db_query_mysql_221_dev / brho` 受控元数据复验，**不声称恢复了原任务的数据上下文，也不声称已验证该业务公式**。

该次真实复验总耗时 181645ms：模型调用合计 175486ms，工具 4977ms，授权 288ms，输出校验 619ms（其中解析 1ms），其余为检查点、序列化和事件等开销。第9次回答的候选表引用不符合真实证据，记录 `OUTPUT_EVIDENCE_VALIDATION_FAILED`；1次有限修复后正常返回。可见耗时主要在多轮推理，不能归因于前端渲染或 JSON 解析。用户所述119.8秒的原任务无法定位，不将这组数据冒充原任务分解。

## 5. 稳定性验证与证据

补充最大分栏宽度测试在整改前构建中复现了真实溢出：125% 等效视口下，工作台宽1044px，左侧拖到520px后内容宽1086px。原因是默认 Grid 将两个侧栏宽度作为不可收缩的固定轨道。修复将侧栏轨道改为可收缩上限，保留编辑器最小宽度；拖拽从实际渲染宽度开始，避免响应式收缩后的首次移动跳变。没有通过新增 overflow 隐藏溢出。

浏览器脚本记录 CDP Layout、重绘、样式更新、PerformanceObserver 长任务和布局偏移，并保存 Trace；检查宿主节点、真实 editor ID、未保存内容、文件 Tab 往返、分栏、光标与可见滚动锚点。数据源导航首次折叠，选择上下文后没有完整表目录请求；第一次展开加载1次，收起重开不重复加载。折叠工程资源后面板与分隔器 Grid 轨道均为0，分隔器 DOM 不存在。

覆盖 100% / 125% / 150% **等效 CSS 视口与 DPR 仿真**。这不是操作浏览器工具栏的原生缩放；不混淆两者。Trace 含首次加载 Monaco 的长任务及用户主动分栏产生的布局变化，不宣称长任务为0，也不使用不同工作量的 Trace 百分比宣称整体速度提升。

证据文件索引和测试结果见 [verification-summary.json](26-stability-evidence/verification-summary.json)。

- [整改前工作台](25-assistant-interaction-evidence/after-workbench.png)、[本轮工作台](26-stability-evidence/workbench-after.png)。
- [真实 SQL 生成](26-stability-evidence/real-sql-generation.png)、[解释](26-stability-evidence/real-explain.png)、[优化](26-stability-evidence/real-optimize.png)。
- [维持担保比例回答恢复](26-stability-evidence/ratio-answer-restored.png)、[真实校验失败与修复诊断](26-stability-evidence/ratio-diagnostics-restored.png)。
- [100% 折叠](26-stability-evidence/left-collapsed-100.png)、[125% 折叠](26-stability-evidence/left-collapsed-125.png)、[150% 折叠](26-stability-evidence/left-collapsed-150.png)。
- [真实模型交互](26-stability-evidence/interaction-browser-proof.json)、[解释与优化](26-stability-evidence/engineering-browser-proof.json)、[原问题受控复验](26-stability-evidence/ratio-browser-proof.json)、[恢复检查](26-stability-evidence/ratio-restore-proof.json)、[性能与状态检查](26-stability-evidence/stability-browser-proof.json)。
- [截断 JSON 的受控 SDK 诊断](26-stability-evidence/controlled-json-repair.json)、[Token 截断与最多两次修复](26-stability-evidence/controlled-output-token-limit.json)。两者由实际 JUnit 检查点导出，明确标记为测试夹具，不代替真实页面验收或原用户报文。

## 6. 修改范围与复现

本轮后端：

- `SqlStudioMetadataAssistant.java`：最终格式适配、分类失败、有限修复与受控诊断。
- 新增 `SqlMetadataOutput.java`：只处理最终展示格式，沿用原 SQL／证据校验。
- `SqlStudioService.java`、`SqlOutputBundle.java`：解释意图与只读结果投影适配。
- `SqlStudioMetadataAssistantTest`、新增 `SqlMetadataOutputTest`、`SqlOutputBundleTest`、`SqlStudioIntegrationTest`。

本轮前端：

- `SqlEditorPane.vue / .js`、新增 `sqlEditorLayout.js / .test.js`：实例诊断、精确监听、布局合并及视图锚点。
- `SqlSchemaExplorer.vue / .js / .test.js`、对应 CSS：默认折叠与真实按需加载。
- `SqlAssistantPanel.vue / .js / .test.js`：插入校验与运行诊断入口。
- `SqlBottomPanel.vue / .js`、新增 `.test.js`：技术诊断展示。
- `sqlIdeState.js`、`sqlMetadataProgress.js / .test.js`：明确错误、修复中状态和尝试耗时。
- `SqlStudioView.vue / .js`、`sql-studio-ide.css`、`package.json`：诊断面板联动、明确分隔器位置、拖拽在 pointercancel／失焦时释放监听，以及测试入口。
- `scripts/verify-sql-assistant-workbench-browser.cjs`、新增 `scripts/verify-sql-stability-browser.cjs`、本记录和脱敏证据。

工程生成／优化使用原 CodingWorkspaceService、Node Runner 与原授权工具。没有新增执行器、连接池、元数据缓存、调度模块或数据库迁移。前一轮的三个区域布局改动继续保留，未重新归为本轮新增架构。

测试命令：

```powershell
$env:NODE_OPTIONS='--no-webstorage'
npm.cmd --prefix chatchat-api/web-app run test:sql-studio
npm.cmd --prefix chatchat-api/web-app run build
npm.cmd --prefix coding-agent-node test
mvn.cmd -o -Pbackend-only -pl chatchat-api -am test `
  '-Dcontainer.image=true' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true' `
  '-Dcoding.sqlglotPython=<已安装SQLGlot的Python绝对路径>' `
  '-Dtest=SqlMetadataOutputTest,SqlStudioMetadataAssistantTest,SqlStudioIntegrationTest,SqlOutputBundleTest,SqlStudioPublicationControllerTest,SqlStudioMetadataServiceTest,CodingWorkspaceServiceTest,DashScopeNativeChatModelTest,RuntimeStreamingChatModelTest,ConfigurableChatModelFactoryTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

前端21个测试文件、126项通过，生产构建完成；后端相关回归109项通过；输出诊断夹具额外重跑21项，与前者重叠、不重复相加。Node 原回归125项通过。构建摘要写入验证索引。浏览器三个等效缩放场景均通过最大分栏宽度、折叠、实例、草稿、光标和滚动锚点检查。

浏览器脚本接受正常登录的受保护 session JSON 和受控工程配置，通过 `SQL_UI_AUTH_FILE / SQL_UI_STATE_FILE / SQL_UI_URL / SQL_UI_EVIDENCE` 指定。稳定性脚本只在指定受控工程创建独立临时 SQL 文件，用于切换页签，最后按版本删除；编辑器原文件不保存，未执行数据库 SQL。Trace 保存在输出目录。AI 脚本的 `SQL_UI_RATIO_REPLAY=1 / SQL_UI_RATIO_READONLY=1 / SQL_UI_ENGINEERING_REPLAY=1` 分别复验原问题、只读恢复和解释／优化。不要对未经授权的业务工程运行验收脚本。

## 7. 尚未完成或不能据此断言的事项

1. 找不到原任务 ID，旧版本没有保存每次原始响应及结束原因，原 `Return one valid JSON output object` 的完整原始报文不能恢复；上述受控复验与诊断夹具不冒充它。
2. 原始现场黑线没有在本轮构建中稳定复现；折叠轨道、DOM、截图和等效缩放检查可证实当前行为，不能替代原生浏览器缩放、特定 GPU 与现场录屏复核。
3. 冷启动仍有 Monaco／语言服务长任务；不宣称消除了所有初始布局变化。对运行中交互的实例、草稿和锚点做实际检查。
4. 现场真实联机模型为当前 OpenAI 兼容协议；DashScope 原生和流式路径属于自动化客户端回归，未宣称完成第二供应商的真实联机切换。
5. 模板新修订的真实 SQL 测试仍受原白名单403约束，见上一轮交付。入口、历史和拒绝路径及相关回归有效，没有扩大授权或把拒绝当作查询成功。SQL Studio 没有因此新增自由执行 SQL 的按钮。
6. 诊断以当前尝试为单位，SDK 内部 HTTP 重试次数未直接暴露；配置重试上限与输出修复次数分开。旧执行链历史仍在原任务事件中。
7. 生产未发布。最终隔离镜像及已修改类校验值保存于 [preview-deployment.json](26-stability-evidence/preview-deployment.json)；上一版容器／镜像保留，可恢复旧隔离 API 与对应网络中的 Node。没有数据回退、索引删除或放宽权限操作。后续正式发布应使用完整构建包并核对所有变更类与静态资源。
