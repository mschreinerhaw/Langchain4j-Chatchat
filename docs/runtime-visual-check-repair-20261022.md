# 图文报告检查状态及历史展示修复

## 联机定位与差异分析

截图对应父任务 `37a99a30-ddce-441e-a43a-a72cffcfa2ef`，Run `att-1-5b4b638a-1c7c-42c8-b238-906f2b6e0d8d`。原 Python 实际执行两次并归档：第一版检查含 PASSED/FAILED，第二版含 PASSED/UNRESOLVED。第二版保留五张图和 1,474 字符正文，但 VisualReportArtifacts 仅接受 PASSED/FAILED，触发 `Invalid computation check`，降级结果还遗漏了正文。

执行成功、产物协议有效、业务检查通过是不同状态。UNRESOLVED 代表尚无足够证据解决问题，不能强制改为 PASSED。复用原 Python Sandbox、ExecutionFeedback、Cline 上下文、任务事件、PG/Silo 归档及图文组件；不重新建设执行框架。

## 改动

- 公共报告生成指令明确 PASSED、FAILED、UNRESOLVED 三种检查状态。UNRESOLVED 使 SUCCESS 降为 PARTIAL，保留图、指标、正文和原检查说明；FAILED 仍使报告失败。未知值依然拒绝。
- 新增 artifactValidationStatus，区分技术协议不合法和业务检查不通过。技术失败反馈 VISUAL_REPORT_INVALID / OUTPUT_CONTRACT，原修复次数、预算、授权与同任务上下文继续生效；不把业务不确定性当成可通过改标签解决的错误。
- 报告降级仍保留经过路径脱敏和长度校验的正文。Java 记录拒绝任务、执行 ID 和异常类型，不输出客户数据或异常原文。
- 既有任务监控增加所有者校验的归档报告展示校验接口。核对 task/run、执行停止证明、代码哈希、结果哈希、manifest 哈希及每份产物哈希后，以当前展示校验器生成视图。不重新执行 SQL/Python/模型，不改写原任务、原代码、检查点和原证据。原图资产继续通过既有幂等归档接口提供引用。
- 历史旧版失败报告由组件根据已授权代码资产找回 run/task，读取重新校验视图；客户端再次匹配原 task/operation/code/result 身份。失败或越权时保留原失败提示，不伪装成恢复成功。
- 前端将 UNRESOLVED 显示为“尚未解决”，与“检查失败”和“分析程序检查通过”区分。

## 验证方式与边界

本地契约、H2 证据重放、策略及组件测试分别记录；它们不代替服务器真实 PG/Silo/MCP 归档验证和浏览器验证。历史报告重新校验限单行、结果 1 MiB、图产物单份 8 MiB、总 64 MiB。检查声明来自原 Python 程序，结构与文件完整性校验不认证业务结论。原第一版 FAILED 及第二版 UNRESOLVED 均保留。
