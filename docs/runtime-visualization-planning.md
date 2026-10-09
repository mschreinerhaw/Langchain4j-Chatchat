# Runtime 图形化规划

图形化规划复用现有总结模型调用，不增加 LLM 调用或重复取数。模型决定结论需要什么展示，Runtime 校验并绑定本次运行已授权的证据，前端负责样式、交互和用户选择。

## 能力目录与注入

`chatchat-common/src/main/resources/runtime/visualization-capabilities.json` 是后端与前端共用的能力目录，`report-block.schema.json` 是声明协议。当前实际渲染器支持 line、bar、pie、scatter、metric、table；没有对应适配器的 area、stacked_bar 不会被广告为可用能力。

`VisualizationCapabilityRegistry` 取目录、部署授权、Agent 授权和前端已注册类型的交集。部署可使用 JVM 属性 `chatchat.visualization.allowed-types`（默认 `*`）；Agent 的维护配置 `workflowConfig.visualizationAllowedTypes` 可以进一步缩小授权。请求中的 `toolInput.visualizationSupportedTypes` 只能缩小前端支持范围，不能增加授权。

`VisualizationCapabilityInjector` 向原总结提示提供交集能力、Schema、数据集 ID、字段名/类型/单位、行数和投影完整性，不重新注入全部原始行。已有统一分析生成 `reportMarkdown` 草稿的路径也在原模型调用中注入，避免最终总结复用草稿时遗漏能力。

## 模型声明

模型在对应结论旁输出 `json:report-block` 围栏。声明只含现有数据引用和字段编码，不含行值、SQL、JavaScript 或图表框架配置。

Runtime 同时识别普通 `json` 围栏中符合声明结构的对象，仍执行相同 Schema 校验。`ReportBlockMarkdownProtocol` 在正文清理边界隔离这些声明；最终发布只保留与本次 Runtime `reportBlocks` 中已编译块完全一致的内容，禁止模型自行宣称已验证。

```json
{
  "id": "comparison-1",
  "type": "chart",
  "chartType": "bar",
  "title": "指标比较",
  "conclusion": "该展示支持正文中的比较结论",
  "reason": "分类比较适合柱状图",
  "datasetRef": "已有的本次运行数据集引用",
  "encoding": { "x": "已有维度字段", "y": ["已有指标字段"] }
}
```

表格使用 `type/chartType: table` 和 `encoding.columns`；指标卡使用 `type/chartType: metric`，绑定一行、一个指标。模型可以不推荐图形；不能为了产生图形编造指标或把查询元信息当作分析结论。

## Runtime 后处理与降级

`VisualizationPlanningNode` 是独立、确定性的后处理组件，不持有模型或工具执行器。它校验闭合 Schema、唯一 ID、注册及授权类型、数据引用、字段、证据引用、数值、单位和图形语义，再编译 ReportBlock。数据限定于本次运行的 `VerifiedReportDataCatalog`，不能通过数据 ID 查询其他运行。

折线要求实际日期有序；指标卡要求单行单指标；饼图要求完整分区，拒绝不完整样本和 Top-N。错误声明移除，正文保留。普通 JSON 中伪造的已验证 ReportBlock 也必须重新经过声明校验。已有 visualization_spec.v2 报告继续经过同一个数据核验器。

结果元数据包含 `reportBlocks`、`visualizationCapabilities`、`visualizationPlanning` 和 `analysisVisualizationAudit`。运行事件单独记录 `VISUALIZATION_PLANNING`，其中 `modelCalls: 0` 表示该后处理阶段未调用模型。前端只按已编译声明渲染，不再根据 Markdown 表格猜测自动图形。用户选择沿用现有偏好保存接口，ReportBlock 使用稳定的 `block:<id>` 槽位。

## 验证

`VisualizationPlanningNodeTest` 覆盖真实值绑定、错误字段/跨运行引用/伪造值拒绝、授权交集、表格、图形语义降级和伪造验证标记。`FinalSynthesisNodeTest` 验证一次总结调用同时提供正文与声明，并产生零模型调用的规划阶段。前端测试覆盖已验证块渲染、普通表格不生成自动图形、偏好持久化及事件显示。
