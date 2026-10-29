# SQL Studio AI 数据库助手工作区重构（2026-10-29）

本轮已完成 Vue 工作区结构调整，并用构建后的真实 SQL Studio 路由、现场隔离 API/MCP、正常登录用户及真实模型验收。没有使用接口拦截、模拟进度或静态演示页。**当前记录是隔离环境验收，不代表本轮代码已发布生产。**

## 一、源码审查与旧布局来源

实际路由为 `/#/w/data-science/sqlStudio`，渲染关系是 `SqlStudioView → SqlSchemaExplorer / SqlEditorPane / SqlAssistantPanel`。组件通过独立 `.js` 和页面 CSS 实现状态与样式，不能只修改某份未被引用的 Vue 文件。

旧结构的来源：

- `SqlAssistantPanel.vue` 将数据库上下文插槽和当前文件大卡片放进聊天滚动区域，还渲染聊天／模板管理导航及单独的协作方式表单。
- `SqlSchemaExplorer.vue` 同时渲染外部搜索框和下拉框；`SqlSchemaExplorer.js` 的 `assetSearch / namespaceSearch` 为这套旧表单服务。
- `sql-studio-ide.css` 的聊天上下文容器曾拥有独立滚动和高度限制，压缩对话空间。侧栏最窄 180px 时，继承的 header Grid 自动最小宽度还会产生横向溢出。
- 模板草稿、SQL 测试、发布、历史和回滚后端已经存在，入口是 `SqlTemplatePublicationPanel → sqlStudioApi → SQL Studio publication API → 现有 SqlTemplateService / TemplateCapabilityService 链路`。本轮没有再创建发布机制。

## 二、最终组件树与状态归属

```text
SqlStudioView（原分栏、工程文件、AI 请求与会话状态）
├─ 项目资源：文件树 + SqlSchemaExplorer 的授权库表导航
├─ SqlEditorPane：保留原 Monaco、模型及编辑接口
├─ SqlAssistantPanel：仅三个布局区域
│  ├─ header：标题操作 + 数据库上下文工具栏
│  │  └─ SqlSchemaExplorer 原状态通过 Teleport 渲染两个 SqlSearchSelect
│  ├─ conversation：唯一常驻纵向滚动区
│  │  └─ SqlAssistantMessage / 原元数据证据、真实进度与审查结果
│  └─ footer：多行输入 + 模式、当前文件、设置、发送／停止
└─ 主工具栏 → 原 SqlTemplatePublicationPanel
```

高级设置和历史使用移到 `body` 的原生模态对话框，不占默认侧栏空间。当前文件在顶部仅显示紧凑名称；输入区通过明确开关关联，并可查看或移除。旧大卡片、旧上下文表单、聊天模板 Tab 已从渲染结构删除，相关旧卡片 CSS 和搜索状态已清理。

`SqlSchemaExplorer` 仍是数据源、命名空间、discovery context 及元数据分页状态的唯一持有者。`SqlSearchSelect` 只保存弹出层的输入过滤和键盘焦点，不能把任意输入文字当成授权 ID。选择器包含全部授权库选项，未选数据源时禁用命名空间；切换和异步回包沿用原串行／快照保护。刷新发现原命名空间失效时清除绑定并提示，避免静默扩大到全部授权库。任务运行时锁定上下文选择。

编辑器上下文通过原 selection 事件和编辑器实例传递。文件／项目切换会移除旧引用；数据库探索默认仅发送问题，只有主动关联后才附带最多 900 字符的 SQL。问题与引用超过现有 4000 字符限制时明确提示，不静默截掉问题。探索不发送工程生成专用的表选择参数。后端继续负责授权与 SQL 执行治理。

SQL 代码块用现有 Markdown 与 highlighter 能力展示，提供复制、插入和替换选中内容。插入／替换使用 Monaco `executeEdits` 和撤销栈，检查真实选区、文件身份、只读／冲突状态，不自动保存或执行 SQL。HTTP 内网页面也有复制回退。模型 HTML 不执行。

## 三、沿用的接口

前端路径均位于 `/api/v1/data-science/sql`，`src/services/sqlStudioApi.js` 未修改。

| 能力 | 现有接口 |
| --- | --- |
| 授权数据源 | `projects/{project}/metadata/providers`、`metadata/assets` |
| 数据库与字段 | POST `metadata/contexts`、POST `metadata/contexts/{discovery}/operations`，原 LIST_NAMESPACES / SEARCH / DESCRIBE |
| AI 请求与恢复 | POST `projects/{project}/generations`、原任务提交／绑定、GET `generations/{generation}` |
| 停止 | 原 Runtime Agent 取消或 SQL Coding 取消接口，依原任务类型选择 |
| 模板 | `files/{file}/template-publications`、`/{revision}/test / publish / rollback` |

没有修改后台 SQL 执行、任务、模板发布协议，没有新增授权逻辑、连接池、元数据缓存或 Coding Agent 能力。

## 四、自动化与真实页面结果

组件／状态测试：**19 个文件，119 项通过**，包含原 SQL 元数据授权、过期回包、会话、代码审查和模板发布测试；新增选择器键盘／非法输入、布局职责、模态设置／历史、快捷键与输入法、重试／修复状态、显式 SQL 引用、安全渲染、复制及插入路由测试。命令：

```powershell
$env:NODE_OPTIONS='--no-webstorage' # 本机 Node 25 下避免实验性全局 localStorage 干扰 jsdom
npm.cmd --prefix chatchat-api/web-app run test:sql-studio
```

Vue 结构检查通过 69 个组件，Vite 生产构建通过；保留原有大包体积警告。依赖未增加。见 [自动化结果与构建摘要](25-assistant-interaction-evidence/automated-tests.json)。

真实浏览器使用 Edge、构建后的同一 Vue 应用和真实隔离服务。正常用户访问已授权的 MySQL 数据源 `db_query_mysql_221_dev` / `brho`；配置库不是由此推断为 MySQL。测试仅查询元数据，SQL 编辑均撤销，未保存用户文件、未执行模型生成的 SQL。

| 检查 | 结果 |
| --- | --- |
| 480 / 336 / 240 / 180px 侧栏 | 均为三个布局区域、两个上下文选择控件，无横向溢出。480 / 336px 上下文高约79px，窄栏约117px；中间 flex/min-height:0，底部不滚动。 |
| 旧组件 | 旧卡片、旧表单、聊天模板导航的 DOM 数量为0。 |
| 选择与刷新 | 清除源立即禁用命名空间，重选与刷新正常，授权选择保持。 |
| 折叠／展开 | 前后为同一个 Monaco DOM 实例。编辑器右侧黑色光标标尺标记已关闭，未再出现原截图中的短黑线。 |
| 真实元数据问答 | generation `da5edc9a-1bea-4671-be5d-48f14c9612bb`，任务 `3214cf37-82a4-320c-aafb-9a83c070a895`，SUCCESS，返回4个候选；点击候选可读取真实可见字段。 |
| 后续 SQL 生成 | generation `488beb80-f2da-418e-b4a1-07f5b2880ae6`，SUCCESS；复用13份证据，工具调用0次，模型2次。显式文件引用与当前 binding 正确传递。 |
| 代码操作 | 高亮、复制、插入、替换真实选区和撤销通过；检查不到文件保存／SQL 执行请求。 |
| 停止与恢复 | generation `cf7ac810-db13-498a-8e82-47b305eb8cee`，真实任务 `34093d7a-d389-3d36-a4a6-c7c51aa1ed59` 进入 CANCELLED，加载停止且出现重试入口；历史问答结果可重新打开。 |
| 模板入口 | 主工作区打开原面板，读取真实已发布状态、SQL／参数快照及历史；不存在聊天模板 Tab。 |
| 模板安全边界 | 对受控常量模板新修订发起真实测试请求，现场返回403，界面显示原因且禁止发布；原活动发布保持不变。**这不算真实 SQL 查询成功。** |

完整回执：[布局](25-assistant-interaction-evidence/layout-browser-proof.json)、[真实 AI 与编辑交互](25-assistant-interaction-evidence/interaction-browser-proof.json)、[模板现场授权检查](25-assistant-interaction-evidence/template-browser-proof.json)。浏览器 `pageerror` 为0。

可复现脚本：[verify-sql-assistant-workbench-browser.cjs](../../scripts/verify-sql-assistant-workbench-browser.cjs)。要求正常登录 session JSON 与授权的受控工程信息通过 `SQL_UI_AUTH_FILE`、`SQL_UI_STATE_FILE` 指定，文件放受保护且 Git 忽略的目录。`SQL_UI_URL` 指定真实站点，`SQL_UI_MODEL` 指定现有模型，`SQL_UI_EVIDENCE` 指定输出目录。`SQL_UI_LAYOUT_ONLY=1` 只验布局；`SQL_UI_TEMPLATE_ONLY=1` 只验模板入口。模板测试需额外设置 `SQL_UI_TEMPLATE_TEST=1`，脚本只允许指定常量模板，默认不发布；`SQL_UI_EXPECT_TEMPLATE_DENIAL=1` 单独验证现场白名单拒绝，不能冒充成功查询。

## 五、页面截图

| 原页面 | 重构后 |
| --- | --- |
| [原工作台](25-assistant-interaction-evidence/before-workbench.png) | [新工作台](25-assistant-interaction-evidence/after-workbench.png) |
| [旧助手](25-assistant-interaction-evidence/before-assistant.png) | [新助手](25-assistant-interaction-evidence/after-assistant.png) |

其他：[336px](25-assistant-interaction-evidence/after-assistant-336.png)、[240px](25-assistant-interaction-evidence/after-assistant-240.png)、[180px](25-assistant-interaction-evidence/after-assistant-180.png)、[真实问答](25-assistant-interaction-evidence/real-metadata-conversation.png)、[真实 SQL 结果与输入区](25-assistant-interaction-evidence/real-sql-conversation.png)、[真实取消](25-assistant-interaction-evidence/real-cancelled-conversation.png)、[已发布模板历史](25-assistant-interaction-evidence/real-template-history.png)、[模板授权拒绝](25-assistant-interaction-evidence/real-template-authorization-denial.png)。

## 六、修改文件清单

以下路径以 `chatchat-api/web-app/src` 为根；组件采用仓库原有 Vue／独立 JS／CSS 组织方式。

- `views/SqlStudioView.vue`、`js/views/SqlStudioView.js`：主工作区入口、单一上下文联动、显式编辑器引用与插入桥接。
- `components/sql-studio/SqlAssistantPanel.vue`、`js/components/sql-studio/SqlAssistantPanel.js`：三个区域、紧凑 composer、真实状态、模态高级设置与历史。
- `components/sql-studio/SqlSchemaExplorer.vue`、`js/components/sql-studio/SqlSchemaExplorer.js`、`styles/components/sql-studio/sql-schema-explorer.css`：删除旧表单、复用授权状态和紧凑上下文工具栏。
- 新增 `components/sql-studio/SqlSearchSelect.vue`、`js/components/sql-studio/SqlSearchSelect.js`、`styles/components/sql-studio/SqlSearchSelect.css`：内部可搜索选择器。
- 新增 `components/sql-studio/SqlAssistantMessage.vue`、`js/components/sql-studio/SqlAssistantMessage.js`、`styles/components/sql-studio/SqlAssistantMessage.css`：安全 Markdown 与 SQL 操作。
- `js/components/sql-studio/SqlEditorPane.js`：原 Monaco 编辑能力桥接与光标标尺设置。
- `js/utils/sqlIdeState.js`、新增 `js/utils/sqlAssistantContent.js`：有界的明确引用、SQL 内容解析与高亮。
- `styles/pages/sql-studio-ide.css`：移除旧卡片样式，固定上下文／输入区域，单一对话滚动及窄栏 Grid 修复。
- 测试：`SqlAssistantPanel.test.js`、`SqlSchemaExplorer.test.js`、新增 `SqlSearchSelect.test.js`、新增 `SqlAssistantMessage.test.js`、`js/views/SqlStudioView.test.js`、`js/utils/sqlIdeState.test.js`、新增 `js/utils/sqlAssistantContent.test.js`；`web-app/package.json` 纳入新增测试。
- 仓库根目录：上述浏览器验收脚本、本记录及截图／脱敏回执目录。

## 七、尚存条件与回退

1. 新修订的真实 SQL 测试在现场被原有数据源绑定／模板白名单治理拒绝：`Datasource binding or template allowlist is not effective`。需通过现有治理入口核准该修订后，再补验成功查询及发布；本轮未扩大权限、未修改白名单或强行发布。原已发布模板及历史保持可读。旧版真实发布／Agent 验收在 [P1交付记录](23-sql-template-publication-p1.md)，不将历史结果称作本轮新增修订成功。
2. 早期真实请求曾返回 `SNAPSHOT_CONFLICT` 409，页面展示失败并可重试，见 [截图](25-assistant-interaction-evidence/real-snapshot-conflict.png)。后续独立联调通过。该后端快照一致性问题没有在纯前端任务中改写或绕过。
3. 取得实际后端 taskId 后才能停止；准备请求尚未返回任务时停止按钮禁用并明确提示。没有伪造客户端取消回执。历史面板复用原 generation 历史，不宣称后端新增独立会话存储。
4. 本轮未更改后端二进制、数据库或服务配置；生产仍需按现有发布流程更新静态资源。回退只需恢复前一版前端资源，不涉及数据库回退。隔离验收新增的受控模板草稿未发布，原 SQL 文件版本及线上活动模板未改变。
