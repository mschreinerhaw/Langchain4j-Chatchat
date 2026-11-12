# Native 图文报告按钮修复（2026-11-12）

功能源码提交：`6dba3ba73e7cdcbf76a0bae713e51d1b91b2a389`；正式聊天消息归一化补丁：`d750a13d550acd32280dfea4101fa9eae950e9ef`。修复已部署到主入口 18100。

问题有三层：Native 能力快照遗漏 `reportMode` 和 `reportPresentation`；已登记的 Native 图表未转换为现有前端所需 `visualReport`；正式聊天消息归一化只保留 `uiResponse`，会丢掉后端 `metadata.visualReport`。仅验证图表组件或工具返回成功不足以覆盖后两层。

现在用户偏好冻结在原任务上下文，经 Native 指令传给 Cline。Cline 自主决定是否需要图表、源码结构、绘图工具和文件名称。Java 不计算业务指标、不生成图表、不设置固定业务报表模板。缺少数据或普通知识问答不强制交付 CHART，不为满足开关编造图表。旧 SDK 分支保持兼容，Native 不使用旧 Sandbox 报表生成指令。

`NativeVisualReport` 只将当前任务、当前 run 的已登记 Silo 图表和模型报告原文映射为现有报告组件的数据。PNG/SVG/Plotly 复用既有格式验证，读取仍走所有者、任务/run、大小及 SHA-256 校验。图表损坏时保留正文并明确标注展示失败；不接受模型提供的浏览器图片 URL。`businessValidationPassed=false` 明确区分交付完整性和业务结论。本次没有加入业务数值校验或修改模型图表内容。

前端将合法 `metadata.visualReport`（含 Agent 包装）纳入 `uiResponse`，保留已有字段，供异步任务完成、消息显示和历史消息流程使用。浏览器验证同时经过真实 `ChatAssistantView.applyResponseMetadata` 与 `ChatMessageList`，并从 18100 实际发布的聊天 chunk 调用该方法，防止只在测试页面直接绑定 metadata 而漏掉正式消息路径。

## 验证与证据

* Java Native/分析/图文回归：API 模块 169 项，168 通过、1 个既有跳过；附件分析入口另行回归通过。见 [回归摘要](evidence/native-visual-report-20261112/regression-summary.txt)。
* 前端 8 个文件、112 项测试通过，覆盖开关参数、消息归一化、图表、排版与兼容行为。
* 通过主环境 `/api/v1/interactions/chat`，使用同一个已授权 Agent、真实模型和明确标记 SYNTHETIC 的 CSV，独立运行 `reportMode=visual` 与 `off`。未提供人工最终脚本或图表。
* 开启时 Cline 自主生成并执行 Python，交付真实 PNG、报告及结果；报告偏好 `density=compact`、`autoImages=false` 原样保留。授权图片下载的字节数和 SHA-256 验证通过，耗时约 206 秒。
* 关闭时返回普通报告，无图文展示元数据，耗时约 135 秒。见 [真实请求验证](evidence/native-visual-report-20261112/chat-verification.json)。
* 真实浏览器加载后端返回的消息及 Silo 图片，确认图片自然宽度有效；正式生产聊天归一化通过，无测试页面脚本错误。见 [浏览器结果](evidence/native-visual-report-20261112/browser-verification.json)、[截图](evidence/native-visual-report-20261112/real-native-chat-report.png)。
* 初次编译暴露测试泛型错误、回归发现旧 QA 行为变化，均已修复；最终回归通过。浏览器脚本对生产 Rollup 导出形态的假设也已修正，没有把失败尝试标记为成功。

图表和文字保持 Cline 原始产物；本验证确认开关、执行、交付和显示，不宣称任意模型报告的业务语义均正确。

## 部署与回滚

最终镜像：`runtime-os-api:native-visual-20261112-r2`，本地 image ID 为 `sha256:4d93d02480199906ff6c5242385702f030b7d44ff9eb99565367eb4c5fbe7d31`。从上述提交源码上传构建，未手改运行容器。Node/Executor、模型动态路由、共享模型配置、MCP、News、Silo 及数据卷未替换。

原始构建和部署记录位于 `/opt/runtime-os/releases/native-visual-20261112-r2/`；真实模型、工具、脚本及产物回执另保存在 `native-visual-20261112-r1/` 和已有 Silo/Runtime Journal。已归档后删除本次两个已停止验收 Worker；当前 Worker 镜像仍在。中间 API 镜像 r1 标签已删除，保留最终镜像及修复前稳定版 API `sha256:4a0aee24a5b9ff1d2ec0fa62e21e31ed67838fffa15e07ef54b15e382255f941` 供回滚，未清理卷或全局缓存。

需要回滚时先排空活动任务，检查是否有后续配置更新，再在服务器运行 `python3 /opt/runtime-os/releases/native-visual-20261112-r2/rollback.py`，仅恢复 API 镜像。主入口和监控返回 200，未登录监控任务查询返回 401。

用户需刷新页面，开启图文报告后重新发送分析请求。该按钮是本次任务的展示偏好，不会后台改写历史报告或自动补画历史图表。
