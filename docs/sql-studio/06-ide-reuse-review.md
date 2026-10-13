# SQL Studio IDE 化复用审查

日期：2026-10-13。基于 `feature/sql-studio-mvp` 当前代码审查，改造范围以前端为主。

| 现有能力 | 复用方式 | 本轮缺口 |
| --- | --- | --- |
| `SqlEditorPane` 的 Monaco、多模型、多标签、草稿与视图状态 | 保留编辑器实例、语言适配器、Ctrl/Cmd+S、格式化及诊断 | Welcome、选中代码交给助手、生成预览 Diff |
| `SqlProjectTree` 与 `sqlFileTree` | 保留工程文件列表和文件路径树 | Activity Bar、统一历史案例、右键菜单、拖拽上传、折叠与尺寸保存 |
| SQL 工程 API、版本、下载、上传 | 原接口继续使用；所有对象访问继续由服务端校验 | 现有 API 没有文件重命名，因此需要一个带版本检查的受控扩展；文件移动不在本轮范围内 |
| SQL Generation + 原 Agent Task | 保留 prepare/submit/bind/poll/apply 流程、工程会话和执行轨迹 | 助手消息流、多轮上下文、任务与文件变更的 IDE 表现 |
| Code Intelligence、EditorPreferences | 直接复用语言设置、补全、格式化、诊断 | 在 IDE 工具栏与状态栏呈现当前语言/方言和问题数 |
| Mapping 文本与原文件下载 | 服务端授权下载；浏览器解析 CSV/XLSX 供表格预览 | 原文件未提供结构化 Sheet API；复杂 Excel 公式/合并单元格的语义无法保证 |

交互实现应保持 Vue 文件仅包含模板并通过外部 JS/CSS 文件承载逻辑和样式。面板偏好与尚未提交的会话草稿可以用现有按身份和 Workspace 隔离的浏览器草稿机制；工程文件、任务与授权数据仍以服务端为准。生成任务在后台继续运行，前端轮询仅负责展示。未经用户确认的编辑不得被生成结果覆盖。

审查后的简化决定：继续使用单个 `SqlEditorPane` Monaco 实例管理工程文件模型，Diff 仅在对比已有文件的 AI 提案时启用；不建立第二套编辑器、聊天任务服务或权限模块。AI 对话组件复用既有 SQL 工程生成和 Agent Task API 的任务语义；当前服务端仍要求至少一个 Mapping，多轮内容通过工程会话和受长度限制的历史摘要传入，不声称支持脱离 Mapping 的通用聊天。
