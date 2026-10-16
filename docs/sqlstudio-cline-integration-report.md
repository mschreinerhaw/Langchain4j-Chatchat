# SQL Studio / 共享 Cline 集成记录

日期：2026-10-16。本轮经用户明确授权兼容扩展。没有新增 Node Worker、平台任务状态机、权限中心或 Evidence 数据库。

## 实际调用链

`SqlStudioView.generate` → 原 `SqlStudioService.prepare`（保留 InterpretationPlan、工程/输入授权、baseVersions）→ 服务端选择 `SQL_WORKSPACE_V2` → `/generations/{id}/coding` → `CodingWorkspaceService.submit` → **同一个 CodingAgentSPI / ClineCodingAdapter / coding-agent-node** → `workspace_read / workspace_propose` → 固定 SQLGlot 工具程序 → Evidence 原子发布 → 原 GenerationView / CodeChangeReview → 用户确认 → 原 `SqlStudioService.apply/write` 原子版本检查和保存。

模型模式的生成、修复、优化在 `chatchat.coding.workspace.enabled=true` 且 Coding/Cline 同时启用时选择新入口。已有 Agent 模式和纯解释保留原路径。默认关闭新 profile；服务端持久上下文决定入口，客户端标志不能为任意任务授权。Coding 生成不能绑定无关 AgentTask；取消经当前生成的 project/owner 检查后传入原 SPI。

## 契约与工具边界

新增 `runtime_os.coding.v2` 的 **WORKSPACE_FILES profile**，四项公共 DTO 形状、公共状态和 canonical hash 算法不变；v1 仍只接受 JSON_LINES，原金样本不变。A2 的快照、后续 revision 和事件保存合同自己的版本，不将 v2 降成 v1。新增独立 `coding-workspace.schema.json`；v2 只支持本轮文件 profile，**不代表旧补数设计中的动态输入 v2 已实现**。

合同只授予 `workspace_read`、`workspace_propose` 与暂存候选文件的 `WORKSPACE_WRITE`。`allowedDataRefs=[]`、`maxCodeExecutions=0`、`networkPolicy=NONE`；不能继承 Runtime Python 的数据挂载/执行权。Node 没有宿主文件、Shell、Docker Socket 或数据库工具。完整文件内容通过受控 proposal 修改，尚无逐行 Patch 工具。

SQL 任务仍提供六项引用：不可变上下文、候选 SQL manifest、**工作区工具/解析操作回执**、候选文件索引、解析/字段校验记录、Evidence Bundle。工具回执明确 `sqlExecuted=false`，不是数据库或 Python Sandbox 执行证明；候选索引不会被 `CodingEvidenceCollector` 当成金融计算 Dataset。最后一次无效提案不能使用前一次成功提案完成；证据发布失败可从持久回执重做发布，不重新解析、不重置预算。公开引用只在完整 bundle 已提交后返回。

## 元数据与 SQL 工具链

前端只提交已展开表的选择身份；`SqlStudioMetadataService.captureCoding` 经原有 MCP Gateway 重新获取授权 DESCRIBE，核验 discovery/binding、命名空间、snapshotId 和精确表名，只投影表/字段结构。模型不能自行指定数据源、连接串或扩大 namespace。提交和结果/应用读取重新核验当前元数据权限和版本；Mapping 与历史 SQL 沿原授权和版本快照流程交付。

`SqlGlotToolchain` 用配置的专用 Python 解释器执行打包的固定 `sql-toolchain/validate.py`，以 stdin/stdout 传输受限 JSON；不执行模型 Python 或 SQL，不调用 Shell。解析限定 10 秒，超时后确认进程停止；无法确认时不出具停止证明。解析后的规范化 SQL 单独归档，保留候选源代码和注释。无元数据时标记 `FIELDS_NOT_EVALUATED`；有完整交付的表结构时检查物理表和字段，CTE 不被误当物理表。语法/字段通过不表示数据库执行、业务口径或执行计划通过。

依赖锁定为 [SQLGlot 30.23.0](https://pypi.org/project/sqlglot/30.23.0/)，wheel SHA-256 写入 `chatchat-api/src/main/resources/sql-toolchain/requirements.txt`。本地安装先遇到 pip 的证书链错误，改用 Windows HTTPS 信任链读取官方元数据和 wheel，核对官方 SHA-256 后离线安装；未关闭 TLS 校验。部署应预装锁定依赖，不在 Coding 时联网安装。

## 状态与验证范围

| 状态 | 本轮事实 |
| --- | --- |
| 已实现并经测试 | 同一 SDK Worker 的读上下文、多文件提案、解析错误修复；真实 Java/H2/Node SDK/SQLGlot 跨进程测试；v1/v2 Schema/hash；SQL HTTP 归属、计划、Diff 之前不写工程、版本化应用及重复应用拒绝；前端测试和构建。模型为明确标注的确定性替身。 |
| 已实现但尚未真实联调 | 新 SQL profile 的真实 provider、完整浏览器普通用户链路、容器版 Worker 与部署网关的联调；本轮未部署生产。 |
| 部分实现 | 当前提供全量内容提案而非逐行 Patch；字段检查限于明确交付的授权快照；Agent 模式尚不转入 Cline；既有 SQL 动态计划展示仍非完整闭环。 |
| 未实现 | SQL 执行计划/实际数据库执行接线、活跃 SDK checkpoint 继续执行、动态补数。生成不会自动取得这些权限。 |
| 被环境阻塞 | 本机 Docker Linux Engine 不可连接，不能新验 Node Docker 重启、沙箱及目标部署拓扑。 |

具体数量和日志见 [端到端验收记录](coding-agent-end-to-end-validation.md)。SDK/SQLGlot 真实运行与真实大模型调用分别记录，不把前者当后者。

## 源码位置和部署配置

- `chatchat-api/src/main/java/com/chatchat/api/datascience/sql/SqlStudioService.java`：prepare/submitCoding/result/codingResult/apply；`SqlStudioMetadataService.java`：captureCoding；`SqlGlotToolchain.java`：validate。
- `chatchat-api/src/main/java/com/chatchat/api/runtime/CodingWorkspaceService.java`：submit/read/propose/complete/recover/output；`CodingWorkspaceProofVerifier.java`：confirmsStopped；同目录既有 Cline Adapter、模型桥和回调控制器。
- `coding-agent-node/src/runner.js`：同一 createCodingRun 的 profile 工具选择；`server.js`：同一服务入口及签名回调。
- `chatchat-api/web-app/src/services/sqlStudioApi.js`、`src/js/views/SqlStudioView.js`、`src/components/sql-studio/SqlSchemaExplorer.vue`：原客户端、任务展示/取消、授权表选择。

参考 `deploy/docker/coding-agent/runtime-coding.properties.example`：启用 workspace profile、指定预装依赖的 `sqlglot-python`。已有 gateway 的精确回调白名单新增两个 workspace 路径；未扩大 Node 出站网络或允许普通管理接口。发布前需更新同一个 Node 镜像、API、前端和网关，单独完成真实 provider/浏览器/容器验收。
