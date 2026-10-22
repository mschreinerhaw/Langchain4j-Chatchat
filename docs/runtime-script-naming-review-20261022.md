# Cline 动态脚本命名：实施前审查与设计

审查基线：86f222245，2026-10-22。

实际物理路径已经由 PythonOperationWorkspace.path 按 tenant/owner/run/task/operation/attempt 的哈希分区；脚本通过 CREATE_NEW 写入临时快照，再以目录移动发布，不存在所有用户共用一个 main.py 的执行目录。固定名称导致代码列表不可辨识，并使未来改名容易漏掉恢复和诊断链路，仍需改造。

固定引用包括 Node runner 的工具描述、schema、文件名校验和 CODE_READY；Java ClinePythonCallbackController、CodingCodeArtifactStore、ClineModelCallbackController、ClineCodingAdapter、CodingTraceArtifactController；ExecutionFeedback 定位栈及 JSON Schema/TS 类型；Python runtime_capture、PythonExecutionLogBoundary；监控页面及对应测试。PythonDockerRuntime 原本支持动态 fileName，执行参数由数组传递，不经 shell 插值。开发车间的用户自定义入口文件与历史测试中的 main.py 是兼容输入，不应全仓盲目替换。

实施设计：在原 DurableCodingTaskService journal 事务中按操作 ID 幂等分配 CodingScript，包含 fileName、scriptPath、workingDirectory、scriptVersion；版本号在 Java 内单调分配，重新提交/修复占用新版本，重复操作不占版本。业务短名从任务目标提取并进行字符/长度限制，UTC 时间使用明确 Z 标记，Task 短码取完整 Task ID 的摘要，避免 analysis-coding 公共前缀被误当短码。

Node 在提交代码前调用 Java prepare-python 获取分配结果，不能自行选择路径。Java execute-python 校验分配描述及源码哈希，MCP 验证 scriptPath、版本与文件名绑定，使用明确路径执行。容器内路径为 /workspace/scripts/<fileName>，每个容器独立挂载原操作级 scripts；workingDirectory 记录带所有者哈希的逻辑目录，物理隔离继续由既有 tenant/owner/run/task/operation/attempt 提供。不会把宿主绝对路径或真实用户名暴露给模型。

代码、版本描述、事件和反馈复用 PostgreSQL journal/证据及 Silo 归档。诊断只允许当前已核对快照的脚本文件名，不接受任意异常文本中的路径。恢复和结果收集从已提交代码版本解析名称，历史 main.py 通过明确的旧记录回退读取，不把固定名称用于新分配。路径穿越、符号链接、跨 owner、同操作不同代码和取消清理边界纳入回归。
