# Agent 发起的数据探索入口

入口审查：原 PromptComposer 已实现 Agent 下拉、发布状态过滤及 agentId 发送；此前 compact 模式隐藏了整个工具栏。AgentChatModeHandler 已将 toolInput.reportMode=visual 传入原 Runtime 分析链路。AgentTaskService 已有持久任务、idempotencyKey、租户/用户身份、状态、事件和恢复，UiArtifactService 已归档 visual-report。无需新建模型调用或执行器。

增量方案：

- 在复用的 compact PromptComposer 中显示现有 Agent 选择器。仅列出发布且 EnterpriseAdminService.canAccessAgent 授权通过的 Agent，未选禁止提交。
- Workspace API 增加 GET /agents、GET /{id}/nodes/{nodeId}/status；Question 增加可选 agentId，老文件分析记录可恢复。
- ExplorationAgentBridge 仅作原 AgentTaskService 的适配：按所选 Agent 配置执行模式，用节点ID构成幂等键，身份由登录会话派生，reportMode=visual。Agent 从自身已授权工具获取数据，不预选或上传归档文件替代 Agent 数据获取。
- 节点持久化 agentId 与 agentTaskId。状态轮询只读取既有任务和归档报告，绝不提交新任务；页面刷新可恢复。图形仍使用 PythonReportChart 与原 artifact 读取/校验。
- 同 Agent 追问创建子节点；父问题和摘要由服务端读取并标记为不可信历史上下文，Agent 重新核实所需当前数据。新节点使用独立会话避免兄弟分支上下文串入。
- 切换 Agent 开始新分析；不把旧文件节点或其他 Agent 的历史数据隐式授权给新 Agent。旧节点原结果保留。
- Agent 无数据、需补充条件或失败时如实显示原结果/状态，不伪造图表，不将任务提交成功认定为分析成功。

主要文件：ExplorationWorkspaceController.java、ExplorationAgentBridge.java、PromptComposer.vue/JS/CSS、DataExplorationView.vue/JS、ExplorationChain.vue、explorationApi.js及相关测试。未增加产品运行依赖或UI框架。

## 自动化与部署

- 探索专项：10个测试文件、75项通过。原图文报告回归：7个文件、48项通过（两组存在重叠，不累加为独立项）。
- 后端：18项通过，包括真实H2节点持久化、无文件Agent入口、同Agent节点继承、发布/授权拒绝、任务幂等参数、读取状态不重新提交、归档报告恢复、旧文件分析契约。
- Vite生产构建、Maven完整打包、Vue组件结构检查及git diff空白检查通过。
- 最终镜像：`runtime-os-api:data-exploration-agent-ready-20261030`；JAR SHA256：`27f06fc59d1c922b5a248a1cc5eae476efdb73d3e0c75b15286a08bcbaa76a59`。备份：`/opt/chatchat-releases/data-exploration-agent-ready-20261030/backup`。
- [部署记录](agent-deployment.json)确认其他容器、会话和消息索引保持不变。原有图表/历史/排序/放大/宽度恢复的[真实部署回归](agent-legacy-regression.json)通过，执行请求0次。

## 真实Agent验证与限制

所有请求均发往真实部署，不拦截或模拟接口。

1. 从空白工作空间选择已发布、获授权的`ids_data_audit`，没有选择或上传文件，提交到已有任务队列。任务`8c931fab-070e-36da-8203-ad610fc2429e`实际执行了绑定的客户服务模板发现工具，但只取得模板元数据，没有取得所问客户资产数值，故没有图表。图形产出验收如实记为[未通过](agent-customer-chart-unavailable.json)，不是图形分析成功。
2. `financial_data_analysis`任务`8f2eb10d-e896-3d76-adaa-9c259152eefd`实际进入Coding Agent和Python Sandbox。它取得37条金融市场观察记录，却不包含所问数据库schema/表统计；原校验报告status=FAILED、charts=0，界面如实显示限制。[图形验收未通过记录](agent-metadata-chart-unavailable.json)保留，不把真实Python执行等同于业务图表成功。
3. 对以上已完成任务进行只读恢复验收：[无数据时不作图](agent-no-data-acceptance.json)、[实际Coding/Python执行但无匹配业务数据](agent-python-no-chart-acceptance.json)均通过。验证保存和刷新恢复Agent、无数据集目录请求、无重复执行，以及实际执行代码SHA校验。PASSED_NO_CHART_HANDLING只表示无图场景处理正确，不表示图形产出验收通过。
4. 原客户分析所用`busi_data_query`当前状态是`recalled`，因此不会出现在可选列表。真实服务端准备请求返回403，没有提交任务，[拒绝证据](agent-unpublished-denial.json)。未擅自重新发布、授权或调整Agent工具绑定。

截图：[输入框内选择Agent](../../target/exploration-agent-live/agent-selected.private.png)、[无数据结果及所选Agent](../../target/exploration-agent-live/agent-no-chart.private.png)、[Coding执行后保留真实数据限制](../../target/exploration-agent-sql-live/agent-no-chart.private.png)。截图含真实业务上下文，仅保留在忽略目录。

正常图形和追问分支已完成，[真实验收记录](agent-visual-acceptance.json)为PASSED：从金融分析节点追问其实际融资融券数据，生成子节点`555cff16-be82-4f69-bfaa-5c2bbbeafd19`，继承同一`financial_data_analysis`，父节点原receipt逐字段保持不变。原生Agent任务`3fc654e4-4a66-3b2a-8b91-a212dacfc0f6`，经现有Coding Agent及Python返回融资余额分布图和明细。实际程序代码和图像产物SHA与归档声明一致，executorStopped=true；保存和刷新恢复节点与Agent，复核execute请求0次、无数据集目录请求、浏览器错误0。

该Agent本次返回PNG静态图，实际渲染[截图](../../target/exploration-agent-visual-live/agent-result.private.png)保留。初始浏览器脚本假定Plotly而失败，已[保留该测试假设失败记录](agent-png-harness-failure.json)，随后仅恢复同一已完成任务，按真实PNG格式验证，没有为重测再调用AI。PNG可查看和放大，不能直接切换为折线图；兼容Plotly的图型修改已由旧图形真实回归验证，不将此PNG用例算作交互图型切换成功。

图表和表格为本次Agent实际返回的49条融资融券数据所生成；本轮核验执行链路、产物完整性与前端关联，不独立认证外部数据系统的生产真实性或全部金融业务口径。客户账户场景取不到数值的限制仍保留，不能用金融市场图表替代客户资产场景验收。

复跑命令：`npm run test:exploration:agent:live`。设置`EXPLORATION_ORIGIN`、`EXPLORATION_AUTH_FILE`（私密登录会话JSON）、`EXPLORATION_AGENT_ID`。已有任务恢复设置`EXPLORATION_WORKSPACE_ID`；显式`EXPLORATION_FOLLOWUP=1`才从当前节点发起新追问。`EXPLORATION_EXPECT_NO_CHART=1`仅验证无图场景处理。原图表只读回归使用`npm run test:exploration:ui:live`。
