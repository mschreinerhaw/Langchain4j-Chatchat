# Cline 动态代码命名与任务隔离交付

版本：1.2.0-20261022；日期：2026-10-22。主体实现 `3031c33af`；中文路径传输及版本显示修复 `363681e08`。实施前先完成了[固定引用审查与设计](runtime-script-naming-review-20261022.md)。

## 实际行为

新 Runtime OS Python 委派不再生成固定 main.py。Java 根据原始业务问题的受限语义短名、UTC 毫秒时间戳、完整 Task ID 的 SHA256 短码和版本号分配名称。例如实际测试中的：

```text
发布验证-用-python-实际读取附件-csv_20261022T081916274Z_b1c69affb5e2_v1.py
发布验证-用-python-实际读取附件-csv_20261022T082121872Z_b1c69affb5e2_v2.py
```

`Z` 明确表示 UTC；页面时间仍可按浏览器本地时区显示。Task 短码取完整 ID 的摘要，避免把所有任务共同的 analysis-coding 前缀当作短码。业务名称只保留字母、数字及分隔符，最多 24 个字符；完整文件名限制 UTF-8 字节数，支持中文。它是受限的名称归一化，不增加模型调用或业务词典。

名称自身不是权限边界。原物理目录本来就按 tenant/owner/run/task/operation/attempt 分区；本次继续使用它，不能把以前“同名”误报成已证实的跨用户覆盖事故。即使不同用户的文件名相同，也必须依赖独立目录和 owner 校验保证安全。

## 分配、执行与修复

新增公共 `CodingScript(fileName, scriptPath, workingDirectory, scriptVersion)`：

| 字段 | 约束 |
|---|---|
| fileName | Java 分配的安全 basename，版本后缀与 scriptVersion 一致，不接受路径分隔符或 `..` |
| scriptPath | 必须严格等于 `/workspace/scripts/<fileName>`；这是容器内部路径 |
| workingDirectory | `tasks/<tenant摘要>/<user摘要>/<run摘要>/<task摘要>`，用于描述任务逻辑工作目录，不向模型暴露宿主路径 |
| scriptVersion | 原任务 journal 事务内单调分配，初始 v1；每次新提交或修复生成新版本 |

实际持久目录仍为受管 workspace root 下的 `operations/<tenant全摘要>/<owner全摘要>/<run全摘要>/<task全摘要>/<operation全摘要>/<attempt>/scripts/<fileName>`。每个容器独立挂载自己的 scripts、input 和 output，容器路径相同不代表共享物理目录。

调用顺序：Node 获得完整代码或 edits 后，以原 toolCallId 绑定的 operationId 调用 Java `prepare-python`；Java 在原 `DurableCodingTaskService` 锁、租约、fencing 和预算检查下分配并持久化身份。相同操作返回同一身份，不再取新时间或增加版本。Node 的 CODE_READY、代码观察、执行请求和监控均使用该身份。

`execute-python` 复核分配身份及代码哈希，并在同一任务 journal 内绑定不可变的版本源码哈希。MCP 校验 tenant/owner/run/task、目录摘要、文件名、代码哈希和版本，保存到原不可变快照；Docker 根据快照中的明确 scriptPath 执行，不依赖 main.py。

失败修复仍在原 Cline 会话中进行，继续受原授权文件、必选工作流、预算、环境版本/镜像快照及 Sandbox 权限约束。修复提交分配 v2/v3，保留 v1 代码、诊断和执行记录。取消或在分配后中断可能造成版本序号空缺，不回收或重用序号；两处监控使用实际分配的 scriptVersion，历史记录缺少该字段时才按原顺序展示。

## 原子性与目录边界

Java 分配、版本递增和源码哈希绑定复用原 journal 数据库事务。不同源码不能绑定到同一 operation/version；过期 worker 和停止中的任务不能提交新版本。

PythonOperationWorkspace 复用 CREATE_NEW、同目录临时快照和目录移动发布。并发提交遇到已经存在的目标时，必须逐项核对原脚本名、源码哈希、参数哈希和版本身份；不覆盖旧文件。准备目录前后检查祖先符号链接；目标必须在配置的 operations/tenant/owner 边界内。产物读取仍必须匹配该操作快照清单。

取消和失败沿用原操作级停止与归档机制，不递归删除其他任务目录。清理仅针对当前未发布的临时快照。

## 数据库、Silo 与日志

未新增数据库或平行执行框架，也不需要新增迁移。PostgreSQL 原任务 journal 的 `script:<operationId>` 保存分配身份和代码哈希；原 CODING_CODE_SUBMISSION 证据、操作记录和反馈保存版本关联。`runtime_workspace_file` 保留代码文件名、哈希、Task ID、Run ID 与 sourceRef 索引。Silo 按原代码/日志资产规则长期保存版本文件及脱敏诊断，不通过命名变更扩大访问权限。

ExecutionFeedback、Java/TypeScript 类型和共享 JSON Schema 增加可空 script 身份；Python 定位栈使用当前脚本 basename。Java 和 MCP 只接受与已分配/已快照脚本一致的定位帧，不把异常中的任意路径传给模型。

Java 现有结构化日志增加 `PYTHON_CODE_SUBMITTED`，并在 `PYTHON_EXECUTION_FEEDBACK` 和未知派发结果中记录 taskId、executionId、scriptPath、scriptVersion、scriptHash、状态及失败分类。原 Node/Cline 交互日志继续保留。

联机测试还发现了旧 HTTP 客户端的中文路径重复编码问题：已编码路径再次作为 WebClient URI 模板编码，产生 `%25E9...`，导致代码下载不匹配及执行代码哈希校验失败。已改用明确 URI 对象，普通下载与流式下载共用正确的单次编码。新增真实本地 HTTP 传输测试先复现 404，再验证两种下载取得相同源码字节；不以这个受控 HTTP 测试替代真实模型端到端验收。

## 回归与验收范围

| 验收项 | 验证方式 |
|---|---|
| 10 个用户并发隔离 | Java 真实 JDBC 并发分配/重读：10 个 owner 使用相同 Task ID，各两个同类任务，共 20 份独立目录/版本记录；跨 scope 读取被拒绝 |
| 实际 Python 并发 | 本地真实文件快照和 Python 子进程 20 次；Linux MCP/Docker 以 10 个独立 owner、相同操作名执行 20 个任务，验证代码、结果、回执、幂等及 PostgreSQL 物理目录均独立 |
| 修复生成 v2 并保留 v1 | 本地真实语法失败及 v2 执行；线上真实 Cline/模型的语法故障任务，逐版本核对 Java 分配、代码哈希、诊断、产物及最终状态 |
| 指定版本与日志关联 | 检查每个版本的 CODE_READY、执行反馈、代码资产及 Java 结构化日志中的 scriptPath/version/hash |
| 服务重启找回历史 | 重启 Java API 后再次获取版本代码和执行反馈，并验证旧 main.py 历史任务兼容性 |
| 取消不影响其他任务 | 在线取消专用操作，核对取消终态，其余 20 个操作代码仍可读取 |
| 非法路径、跨用户目录及访问 | 本地路径穿越/版本后缀校验；在线非法 scriptPath、跨 owner 工作目录与产物访问均被拒绝 |

最终结果：真实模型验收父任务 `3b1e67d2-a552-338c-9275-a2ebec6a793c`，编程任务 `analysis-coding-59cbd7d11e4ce41f23afaab9dc141b106e8fd30edf8ce6d5a1c3daee9d7c26dc`，均已成功完成。九次真实模型调用、262,691 Tokens、四个代码版本；v1 为语法失败，v2 修复成功，v3/v4 为成功后的结果复核版本，最终合成金额 CNY 33.68。四份 Silo 代码下载哈希、PG 索引、CODE_READY 和 Java 日志逐项匹配；重启 Java 后记录完全一致。真实 Edge 验证产品监控与 Node 控制台都展示动态名称及实际版本号，没有页面异常。Node 控制台通过临时本机 SSH 隧道检查，没有扩大服务器暴露端口或权限。

完整[机器可读验收证据](evidence/runtime-script-naming-20261022.json)同时保留首次联调发现的中文路径问题及修复后的验证，不将首次失败计为通过。并发 Docker 演练使用服务端受管协议中的 10 个 synthetic owner，不是新建 10 套产品登录或同时消耗 10 路真实模型配额；真实 Cline/模型链路单独验收。不会将组件结果冒充十路完整前端登录测试。

本次选定核心 Java 回归共 763 例：744 通过、19 个需要本地 Docker 的用例跳过，零失败；另有 MCP HTTP 客户端测试 3 例通过。Node 46 例、前端监控 9 例、Python 诊断测试 4 例通过，Vite 构建通过。此前环境缺失的四个本地 Spring 授权集成用例仍未计入通过；不是全仓库任意历史测试均通过。

## 兼容性及部署

新生成链路内 main.py 固定引用已移除。仅保留 `CodingScript.LEGACY_FILE` 用于历史读取与旧调用兼容；测试中的 main.py 用于验证旧记录，开发车间 IDE 的用户自定义入口文件保持原行为。固定的 Runtime 协议 SDK/helper 文件名不是 Cline 生成的业务脚本，不参与业务版本命名。

部署地址：192.168.195.221；Compose 项目 runtime-os-app-stage。API/Node 使用 script-naming-r2 镜像，MCP 使用 script-naming 镜像；配置、挂载及无关容器均保持不变。API 镜像 743,679,162 字节，MCP 584,018,085 字节，Node 393,644,993 字节，Java 镜像各只有一层 app.jar；没有通过叠加旧业务镜像发布。

发布、回滚备份和证据分别位于 `/opt/chatchat-releases/1.2.0-20261022-script-naming` 及 `...-script-naming-r2`。应按包内固定镜像清单整体更新 API/Node/MCP，避免新 Node prepare-python 与旧 API 混用。旧任务代码不批量改名，避免破坏已有哈希与不可变证据。

新离线更新包：`/opt/chatchat-releases/chatchat-1.2.0-20261022-script-naming-offline.tar.gz`，898,851,678 字节，SHA256：`8eccad9acd42bd469cada60bd3b83b78279fdb751411a3dd11f6536b126bd588`。[包回执](evidence/runtime-script-naming-package-20261022.json)记录固定镜像 ID。包内含三项应用镜像、校验清单、安装/回滚说明及验收证据，依赖既有 1.2 安装，不是空服务器初始化包。没有打包本次会话凭据、密钥文件、客户数据或私有回滚备份。包内报告不含包自身的最终摘要，以包外回执为准；此前发布包保持不变。

边界：本次验证终态任务重启后的版本恢复，不承诺覆盖每个掉电/进程崩溃窗口；逻辑目录摘要是分区描述，不是独立访问凭证。产物 INCONCLUSIVE 只表示原结构/来源校验未失败，不证明任意业务结论正确；合成金额另行验证。已有逐操作输入复制及资源实际计量限制与前次交付相同，本次未扩大改造范围。

## 主要文件

| 模块 | 改动 |
|---|---|
| 公共契约 | CodingScript、CodingRuntimeContext、McpPythonControlPlanePort.StartRequest、ExecutionFeedback、共享 JSON Schema、TS feedback 类型 |
| Java 任务与回调 | DurableCodingTaskService 原事务分配/绑定；ClinePythonCallbackController prepare/execute/get；CodingCodeArtifactStore；CodingPythonExecutionBridge；CodingExecutionFeedbackService |
| 恢复及结果读取 | ClineCodingAdapter、ClineModelCallbackController、CodingTraceArtifactController 从任务/版本解析文件名 |
| HTTP 传输 | McpPythonControlPlaneClient 单次编码的 URI；新增 Unicode 字节及流式下载回归 |
| Python Runtime | PythonManagedExecutionService 请求/回执/指纹；PythonOperationWorkspace 不可变快照与 owner 边界；PythonDockerRuntime 明确 scriptPath；runtime_capture.py 动态定位及 UTF-8 子进程流；PythonExecutionLogBoundary 当前文件绑定 |
| Node/页面 | runner.js、server.js、monitor.ts、monitor-types.ts、web/main.ts；CodingTaskMonitor.vue、codingMonitor.ts |
| 测试 | CodingTaskPersistenceTest、PythonScriptIsolationIntegrationTest、ExecutionFeedbackContractTest、相关恢复/回调/Runtime 测试及 Node 模型/监控夹具；D1 smoke 收集按实际文件名读取 |
