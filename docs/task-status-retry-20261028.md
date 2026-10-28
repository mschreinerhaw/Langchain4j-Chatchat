# 任务状态、重试与事件更新修复

截图对应任务 `18dd6a61-6d20-4506-8179-ca3cc0c8a8b4` 的后端真实终态为 `SUCCESS / SUCCEEDED`，事件216条。其中120、121是分析操作失败，后续仍有执行记录，215为成功ANSWER、216为成功COMPLETE。旧展示逻辑把中间操作的失败锁定为整个任务失败，因此出现报告已经生成、工作台仍标记失败的矛盾。

另一个独立问题是事件订阅遇到ERROR/RESULT/COMPLETE就结束；它没有重新核对任务快照，可能把重试轮次、阶段结果或旧尝试的结果作为任务最终结果。恢复历史时，RETRY_WAIT、REPAIRING、EXECUTING等状态也未完整纳入继续跟踪范围。

此次改动：

- 工作台以任务快照的canonicalState为准。失败操作仍保留；运行和修复中的父任务继续显示运行/重试及转圈。
- 事件只作为候选结果。订阅和轮询在关闭前重新读取任务快照，核对当前attempt、最终结果，并等待已知事件序号全部读完。
- 阶段结果、失败轮次和网络断流不把任务判成失败。SSE断开转为按原taskId和cursor轮询；不重新提交任务。
- 历史页面重新核对真实状态，修正已错误保存的失败展示。后端仍在执行时重新跟踪原任务。

涉及文件：`agentTaskTerminalEvents.js`、`ChatAssistantView.js`、`runtimeObservationPresentation.js`、`ChatMessageList.js`及对应测试；增加脱敏后的真实216事件回归夹具 `src/js/utils/fixtures/retried-stock-task.json`。没有修改后端调度/重试流程，也没有重置原任务。

验证结果：相关Vitest测试93通过，终态工具Node测试4通过。更广的UI artifact回归338通过、1失败，失败是原MCP管理按钮的源字符串样式断言；该测试和对应页面没有本次改动，不能将整个广泛回归标为全通过。

已发布到现场Web入口 `http://192.168.195.221:18100`。使用预载Nginx镜像离线构建前端资源层，仅更新网关；API、MCP、News、Coding Agent、Node sidecar容器ID及镜像均保持不变，没有重启后台任务。主API镜像/JAR不变，直接访问仅宿主机可达的API端口仍是其原静态资源；对外Web入口使用本次前端。模型超时页面的前序未发布改动没有混入本次现场前端。

现场发布目录 `/opt/chatchat-releases/task-lifecycle-20261028` 保存原网关、Compose和配置用于回退。原网关是手工管理容器，Compose单独up会名称冲突；首次尝试未替换运行容器并恢复了文件，随后通过保留原配置的容器替换完成仅网关升级。旧网关备份容器和旧镜像保留。

浏览器实测：读取该真实任务全部216事件后显示completed；真实SSE也收到216事件、cursor=216，以成功ANSWER结束；回放前121事件时保留两条失败操作而总状态为重试中，继续加入到214条后仍有加载标识，只有切换为真实成功快照后停止。浏览器无pageerror。已打开的旧页面需要刷新一次以加载新资源。

公开证据：[验证摘要](task-status-retry-20261028-evidence/verification-summary.json)、[浏览器验证](task-status-retry-20261028-evidence/browser-verification.json)、[部署检查](task-status-retry-20261028-evidence/deployment.json)、[真实任务完成截图](task-status-retry-20261028-evidence/actual-completed.png)、[重试回放截图](task-status-retry-20261028-evidence/repairing.png)。登录凭据与原始回答不包含在公开证据中。
