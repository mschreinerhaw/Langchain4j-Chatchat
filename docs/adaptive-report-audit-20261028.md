# 图文报告排版审查（实施前）

当前已有三条兼容展示路径，不应再建设一套报告存储系统：

- `python_visual_report.v1`：Cline 在同一 Python 程序生成 rows.jsonl、Markdown、指标/章节/表格和 PNG/SVG/Plotly 文件；`VisualReportArtifacts` 从已停止、hash 校验的执行产物中接纳，经 `CodingEvidenceCollector` 归档为现有证据。`UiArtifactService` 保存 visual-report JSON 资源，历史消息恢复使用同一资源及执行身份。图片来自已授权归档文件，不能使用任意模型 URL。
- Runtime 通用分析：`AdaptiveReportGenerationSpec` 已要求动态章节；`VisualizationCapabilityInjector`、`report_block.v1` 允许 Markdown 结论旁绑定已验证 dataset 的指标/表格/图表。它不等于 Python 图文报告，也不需要迁移既有 Markdown 记录。
- `analytical_report.v1` 和原 Markdown/HTML UiArtifact：已有渲染与导出兼容路径，继续保留。模型排版不得开放 HTML/JavaScript 执行权限。

固定感的直接来源是 `PythonVisualReport.vue`：头部摘要后必定渲染全体指标，再按章节渲染文字、chartIds、tableIds，最后补剩余图表。CSS 用 auto-fit 而非固定四列，schema 也不强制四指标；因此“永远四张卡”不是协议硬约束，而是缺少可表达混排顺序的模型输出、统一布局偏好与前端固定渲染顺序共同造成。现有 `VisualReportMode.INSTRUCTION` 虽要求动态选择，但 sections 没有稳定 ID、报告没有布局顺序协议，模型不能把异常证据或结论放到指标前。

现有图文开关位于 `PromptComposer`，请求经 `toolInput.reportMode` → Agent/Cline 属性 → CodingRuntimeContext 任务快照 → 模型 objective。没有密度、模板或自动配图配置。复用该入口与现有 workspaceDrafts 作用域持久化；同时把选定配置固化进报告资源，使历史渲染不读取用户后来改过的偏好。

计划最小扩展：保持 python_visual_report.v1，以可选 report_layout.v1 布局对象、presentation 偏好、章节ID和数据引用扩展，不替换原正文、计算结果或归档协议。统一 Vue ReportRenderer 复用已有 PythonReportChart 与安全 Markdown，旧 PythonVisualReport 作为兼容入口。Java 校验引用/组件白名单，客户端再次校验；非法布局保留原分析并退回兼容布局，不为排版重新查询数据或执行 Python。

Cline Skills 来源于现有任务技能快照和披露机制，报告输出硬要求目前来自共享 `VisualReportMode`，不应向每个 Skill 复制一套提示词。修改共享指令并经原单次模型/程序链路传入，不增加常规排版模型调用。已有 artifact_read 修复预算保留。
