# 数据探索 UI 专项：审查与增量计划

基于第一阶段已运行的工作空间和用户最新两栏原型实施。第一阶段部署、三次真实 Coding/Python 执行和数值核对见04号报告，不重建分析能力。

## 现有代码与保留范围

| 真实文件 | 当前作用 | 本次处理 |
|---|---|---|
| `chatchat-api/web-app/src/views/DataExplorationView.vue` | 三栏、数据勾选、全部报告、常驻配置 | 改成紧凑工具栏、可调思维链、单主图区域及底部输入 |
| `chatchat-api/web-app/src/js/views/DataExplorationView.js` | Vue ref 状态，prepare/execute/save，URL 节点恢复 | 保留请求和持久化链路；区分分析/保存/刷新状态，切换历史节点不阻塞或重新执行 |
| `chatchat-api/web-app/src/styles/pages/data-exploration.css` | 三栏布局 | 仅改本页布局；复用现有颜色、按钮和外壳规范 |
| `chatchat-api/web-app/src/components/AssistantLayout.vue` | 应用导航和工作区域外壳 | 保留，不另建布局框架 |
| `chatchat-api/web-app/src/components/PromptComposer.vue`、`src/js/components/PromptComposer.js` | 既有输入、IME、发送、加载状态 | 增量 compact 模式和可配置提示，默认行为不变 |
| `chatchat-api/web-app/src/components/PythonReportChart.vue`、`src/js/components/PythonReportChart.js` | 产物鉴权、校验、隔离 iframe、Plotly | 复用；本页隐藏重复标题；展示切换用已载入数据局部更新 |
| `chatchat-api/web-app/src/js/utils/pythonVisualReport.js` | checkedPlotlyFigure、离线 frame、数据降级 | 保留安全边界；支持已有图形的局部重绘 |
| `chatchat-api/web-app/src/js/utils/explorationPresentation.js` | 原 bar/line 数列保持转换 | 扩展受限兼容判断、scatter/单序列 pie；不兼容时提示，不补造数据 |
| `chatchat-api/web-app/src/js/utils/explorationGraph.js` | Runtime 执行事件图 | 不改为业务树；本页直接使用已存 parentId，轻量呈现父子分支 |
| `chatchat-api/web-app/src/services/explorationApi.js` | 既有 apiRequest 适配 | 保留；主页面不再调用数据目录列表 |
| `chatchat-api/src/main/java/com/chatchat/api/exploration/ExplorationWorkspaceController.java` | owner 隔离、节点与配置 API | 只增量支持从已有节点创建空间并继承授权数据，以及兼容展示类型枚举 |
| `chatchat-api/src/main/java/com/chatchat/api/exploration/ExplorationWorkspaceStore.java` | JSON 文档、CAS 版本 | 保存空间初始数据引用，沿用原表和版本机制 |

既有 SQL 项目树绑定文件/目录操作，不适合直接作为分析节点树；没有可直接复用的通用分页数据表。仅新增轻量业务树/表格 UI 组件与纯展示辅助函数，不引入 UI 框架、状态库或图表库。

## 布局与交互

顶部只显示产品名、业务空间切换、刷新分析、保存节点。创建空间收进切换入口弹窗。左侧仅“思维链”，约22%，可拖动和折叠，节点标题使用报告业务主题，原指令和证据移入按需详情。节点按真实 parentId 展示分支，可局部展开；切换直接读取已保存产物。

右侧只展示短业务主题/摘要、当前图表、多图轻量 Tab、默认收起的数据预览。图表顶部放兼容类型、放大和数据入口；分页/排序不改变原数据。输入复用 PromptComposer 的 compact 模式，固定在本工作区底部。

数据集列表、搜索、文件勾选、常驻配置侧栏全部移除。追问继承当前节点 fileIds/contextIds；新空间可从当前节点继承既有授权数据引用。没有交付数据时明确显示空状态，不自动选任意用户文件，也不增加数据权限。用户空间原管理入口保留。

刷新恢复既有空间、选中节点、图表配置；宽度/折叠等纯布局偏好按当前用户空间隔离保存。模型任务运行时保持历史结果可浏览，完成后合并服务端状态而不强制切回用户已离开的节点。

## 验证计划

保留整改前真实截图；测试业务树分支/折叠/历史切换、原指令隐藏、布局拖动/折叠、表格分页排序、兼容类型转换与原数列保持、输入继承与恢复、空间继承 owner 验证；回归原 PromptComposer、PythonReportChart 与持久化。部署后用已有真实空间和一次新分支追问验收，保存整改后截图及请求计数。禁止用示例静态值或原型图中的数字替换真实结果。
