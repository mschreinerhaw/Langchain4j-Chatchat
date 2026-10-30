# UI 专项交付与验收

本次在 `feat/ai-data-exploration-workspace` 分支增量整改既有页面。审查、复用与文件计划先写入 [05-ui-source-review.md](05-ui-source-review.md)，再实施页面修改。未新增 Agent、模型调用通道、Python 引擎或完整 UI 框架。

## 页面与文件

工作空间采用紧凑顶部、左侧可调/可折叠思维链、右侧单图主区与轻量多图 Tab、工作区底部输入。数据集列表、搜索、勾选及常驻图表配置从页面移除；原用户空间管理入口和授权校验保留。原任务问题、完整结果、数据限制及执行凭据收进按需说明。图表数据明细默认收起。

| 文件（相对仓库根目录） | 变更 |
|---|---|
| `chatchat-api/web-app/src/views/DataExplorationView.vue` | 真实页面两栏整改、工作空间切换弹窗、图表/表格放大、说明弹窗 |
| `chatchat-api/web-app/src/js/views/DataExplorationView.js` | 已有 API 接通；历史选中、追问分支、局部状态更新、图表缓存、布局偏好 |
| `chatchat-api/web-app/src/styles/pages/data-exploration.css` | 两栏、紧凑工具栏、固定工作区输入、响应式布局 |
| `chatchat-api/web-app/src/components/ExplorationChain.vue` 及同名 `src/js/components/*.js`、`src/styles/components/*.css` | 真实 parentId 树、局部分支展开、业务标题/简短描述 |
| `chatchat-api/web-app/src/components/ExplorationDataTable.vue` 及同名 JS/CSS | 原始图表数列对应表格、分页、稳定排序 |
| `chatchat-api/web-app/src/components/PromptComposer.vue`、`src/js/components/PromptComposer.js`、对应 CSS | 复用原输入框，增加 compact、placeholder、数据未交付时禁用输入；普通会话默认行为保留 |
| `chatchat-api/web-app/src/components/PythonReportChart.vue`、`src/js/components/PythonReportChart.js`、对应 CSS | 紧凑展示、ready 元数据、同源产物缓存、KeepAlive 恢复、展示配置变化不重载产物 |
| `chatchat-api/web-app/src/js/utils/pythonVisualReport.js` | 离线 Plotly 在既有隔离 iframe 内使用 react 局部重绘，原鉴权和校验边界保留 |
| `chatchat-api/web-app/src/js/utils/explorationPresentation.js` | 柱/线/散点及受限单序列饼图转换；精确数值表；8位业务日期仅格式化轴标签 |
| `chatchat-api/web-app/src/js/utils/explorationWorkspace.js` | 业务名称、树关系、稳定排序纯函数 |
| `chatchat-api/web-app/src/services/explorationApi.js` | 复用 apiRequest；新建空间可传已有空间/节点引用 |
| `chatchat-api/src/main/java/com/chatchat/api/exploration/ExplorationWorkspaceController.java` | 原 Workspace API 增量继承授权数据；扩展 presentation 枚举 |
| `chatchat-api/src/main/java/com/chatchat/api/exploration/ExplorationWorkspaceStore.java` | 原 JSON/CAS 机制持久化空间初始 fileIds，无新表 |
| `chatchat-api/web-app/package.json` | 加入专项单测及真实 UI 验证命令，默认 test 包含探索回归 |

测试文件：`DataExplorationView.test.js`、`explorationWorkspace.test.js`、`explorationPresentation.test.js`、`ExplorationDataTable.test.js`、`PromptComposer.test.js`、`PythonReportChart.test.js`、`ExplorationWorkspaceTest.java`。真实浏览器脚本：`chatchat-api/web-app/scripts/test-data-exploration-ui-live.mjs`。

## 自动化结果

- `npm run test:exploration`：10 文件、70 项通过。
- `npm run test:visual-report`：7 文件、47 项通过，含原图文报告、输入框和渲染器。两组去重共99项。
- Maven `ExplorationWorkspaceTest,ExplorationWorkspaceMigrationTest,AssetAnalysisControllerTest`：14项通过，包括真实 H2 持久化、跨用户/租户隔离、源空间引用继承、恢复、旧执行链路契约。
- Vue 独立脚本/样式结构检查：72组件通过；Vite生产构建、完整 Maven JAR 打包通过；git diff 空白检查通过。
- 单元测试明确模拟 API 边界，不作为 Coding/Python 真实执行的证据。
- 分页23行场景由组件测试覆盖；部署客户快照仅5行，真实浏览器验证该5行的排序、收起和展开。

## 截图与部署

真实登录页面截图，均来自实际服务与已归档产物，无静态替换：

- [整改前](../../target/data-exploration-ui/before.private.png)
- [整改后（数据预览默认收起）](../../target/data-exploration-ui/after.private.png)
- [历史节点追问形成分支](../../target/data-exploration-ui/branch.private.png)

截图含客户标识，保留在本地忽略目录，不作为公开仓库资源上传。

测试服务：`http://192.168.195.221:18100/index.html#/w/data-science/dataExploration`。
镜像：`runtime-os-api:data-exploration-ui-fit-20261030`。
JAR SHA256：`2e8e9d93a9c36d8aef89fa29f28f4e8729732e3182f39634d33fbd7e6bd502f7`。
备份：`/opt/chatchat-releases/data-exploration-ui-fit-20261030/backup`。
部署检查确认其他服务容器、原会话内容和消息索引数量保持不变。Coding Agent、MCP、Python Sandbox 均沿用原服务。

## 真实交互证据

部署验收 JSON、真实新分支的 task/operation/代码哈希及结论见 [ui-live-acceptance.json](ui-live-acceptance.json)。原始响应、图表字节和截图保留在 `target/data-exploration-ui/`。

真实浏览器验收 PASSED：默认无数据集列表/常驻配置，表格默认收起，输入固定在工作区底部。历史切换、保存、柱/线/散点切换期间 execute 请求0次；图表切换保留同一iframe，产物读取次数不增加，图中x/y与SHA校验后的原始字节逐点一致。多序列不兼容饼图选项禁用并解释原因。表格排序/展开、图表放大成功；刷新恢复选中节点、图表配置和侧栏宽度，浏览器错误0。

从历史根节点创建新子节点 `d60b9b1d-cd99-4536-ad00-b679a87a5062`，与原有子节点形成兄弟分支。继承原fileIds及归档contextIds，追问execute恰好1次。taskId为 `analysis-coding-19877440d3cc98086025a1236e28e83976b1fe2df3a387565b839319e4604aa6`；operationId为 `cline-e3cc9fcfbae476819c8bbee567e50c5bfcf3b5de9b0e99b057a88c3cf79dfd08`。真实执行executorStopped=true，代码产物SHA与回执codeSha256一致，有6条关联证据。执行中可浏览旧节点，完成后保持浏览中的节点选择；原有3个节点receipt逐字段不变，新节点保存成功。

独立核对：新图5个资金余额点、对应表格5个值和日期均匹配原始归档字段cptl_bal；[数值核对](ui-branch-numeric-verification.json)共10次比较通过。单位保持未声明，报告PARTIAL如实保留。

复跑：设置EXPLORATION_ORIGIN、EXPLORATION_AUTH_FILE（私密认证JSON）、EXPLORATION_WORKSPACE_ID和EXPLORATION_CORRECTED_NODE_ID（三数列的核对节点），运行npm run test:exploration:ui:live。默认只恢复与验证既有结果；显式EXPLORATION_RUN_FOLLOWUP=1才产生新分析。设置EXPLORATION_BRANCH_NODE_ID可额外核对新分支刷新和自适应图表。

最后展示修正取消归档图表固定宽高，并统一数字/ISO业务日期标签；不修改归档产物。补充验收曾因脚本新增中文选择器经过终端编码后变成问号而超时，修正选择器后重跑，不因此重新调用AI。

最终镜像的[展示复核](ui-final-presentation-acceptance.json)也为PASSED：新增鼠标拖拽宽度、分支收起展开、新分支刷新恢复、Plotly实际宽度匹配工作区及日期标签检查，execute请求0次、浏览器错误0。自动化等待实际Plotly类型和mode更新，避免在异步绘制完成前断言旧图；这项复核不重新执行已完成分析。[部署记录](ui-deployment.json)包含最终JAR校验和、备份及会话保持检查。

## 数据限制

使用的是既有执行档案中实际客户1500007073的5个交易日资产快照（2026-08-03至2026-08-07），不是原截图所写客户070200046604的12个月数据，也不是本轮新查询的生产客户数据。数据源未声明单位；核对后的节点使用“原始单位（未声明）”。历史节点原始凭据不被UI整改覆盖，旧节点曾有单位标注问题，已保留第一阶段报告的限制说明与修正节点。

不能将5日档案验收宣称为全量/实时生产数据验收。数据不足时维持PARTIAL/数据有限；UI不补造数据。复杂多序列、负值、重复类别和非纵向XY结构不直接转换成饼图；下拉项提示原因。节点缩略图是可选项，本版以紧凑树与简短描述呈现。
