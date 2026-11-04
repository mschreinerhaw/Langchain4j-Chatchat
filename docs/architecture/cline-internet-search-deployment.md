# 联网搜索集成部署与回退

环境：`192.168.195.221:18100`；应用目录 `/opt/runtime-os-docker-stage`。认证信息由运行环境提供，本文件不记录口令、API Key、共享回调密钥或认证 Header。

## 发布产物

已部署 `/opt/chatchat-releases/cline-report-skills-internet-r12`，来源提交 `82f404b9e7ac7ce701041084cf63d68fed407f8e`。API/MCP/News 使用完整 Maven Spring Boot JAR；Worker 复用 R11 的完整源码、dist、Skills 与测试归档。没有对运行容器注入替换 class 或只上传散落静态文件。

| 服务 | 镜像 / 内容 |
|---|---|
| API | `runtime-os-api:cline-internet-82f404b9e7ac`，SHA256 `15792b722853f9625f33ad13ebd102fb89b79ce8e00b95a5a2a364b7a277a2fc` |
| MCP | `runtime-os-mcp:cline-internet-ef48a119f1d7`，SHA256 `99630cf5eb2bc0ab3ba2da441ba96473f4b94022fc0d216316206ab124710d2d` |
| News | `runtime-os-news:cline-internet-ef48a119f1d7`，SHA256 `45c77705c098fb12386d566c224e34e6030d5d22b2c44bbec53eb53732c8fce7` |
| Worker | `runtime-os-coding-agent:agent-skills-internet-1cda61e82e80`，镜像 ID `sha256:533242dc003497c2e387e2a50a23daad0eb96a6ec00f8a0bd46a7bfb5e8ed56e` |

Worker 归档 SHA256：`9a5dd7fe8e4418b4c568d179f1627a09a004926f50607c053473466b186f90c7`。原生 SDK 版本保持 `0.0.93`。最终实际容器信息以[脱敏部署记录](../testing/cline-internet-search-evidence/deployment.json)为准。

传输 JAR 时使用字节差量节省传输量；服务端重建的是与本地 SHA256 完全一致的完整 JAR，再构建镜像。差量传输不等于运行时热补丁。

## 发布保护

`prepare-cline-internet-stage.py` 验证完整 JAR、Worker 归档及模块哈希，使用 `--network none` 离线构建和测试。`deploy-integrated-runtime.py` 在替换前检查活动 Runtime 任务、Worker 会话及 Python 容器；活动任务存在时拒绝切换。R10 曾因 C8 验收任务仍活动而正确拒绝部署，没有替换运行镜像。

R1/R2 在替换 News 前停写，再保存 H2 与配置，避免复制写入中的 H2。API/MCP 使用 PostgreSQL dump。后续发布只替换内容发生变化的服务；R11 仅替换 Worker，R12 仅替换 API 与其节点代理。所有备份保留在对应 release 的 `backup/`，旧容器保留为 `*-before-studio-*`。

脚本校验会话内容、消息索引数量及其他容器未受未评估变更影响，并恢复容器身份对应的出口规则。最终目录保留 manifest、deployment、完整产物、离线测试结果和备份。使用完整包验证前端引用资源存在，避免混入旧 index.html 与新哈希资源。

## 配置与数据

- API：`platform_model_internet_policy` 增量保存模型开关、Provider ID、revision。默认关闭，保存即可动态生效，不依赖发布模型。
- News：Provider、配额、调用审计保存在数据库，凭据由现有服务端密钥 AES-GCM 加密。模型页面与 Coding 合同均不包含密钥。
- 只启用已有配置的腾讯 WSA 完成实测；Brave/Tavily 无有效凭据时保持停用。故障测试 Provider 在测试后停用。
- `internet_research_acceptance_20261105` 是现有 RoleChat 类型的验收配置，无绑定私有文档。保留它及授权以支持来源历史复核，不创建新的业务分析 Agent 类型。
- 现有 News 来源、索引和 Temporal 采集链路保留；旧外部搜索调用方保留兼容路径，Cline 新路径不调用 Java 自动外部搜索回退。

## 回退边界

优先在模型页面关闭联网开关。服务端 revision 立即失效旧任务的后续联网调用；重新打开也不会复活旧 task binding。历史公开来源仍通过当前用户资源权限读取已归档快照，不重新联网。

Worker 的兼容回退目标是 R9 Worker：`runtime-os-coding-agent:agent-skills-internet-8797cec5d60e`，镜像 ID `sha256:313d602f2e38473ab5f2a71fedca24cb95535f1264ae3726d2104d8d08b7e44f`。R11 的 `deployment.json.retired` 记录其保留容器 `runtime-os-app-stage-coding-agent-1-before-studio-1793816546`；回退前核对实际镜像。API/MCP/News 不随 Worker 回退。

API 回退依据 R12 的 `deployment.json.retired`，对应 R11 所用 `runtime-os-api:cline-internet-7ca34b65a0aa` 及其节点代理。该版本能读取新的联网合同，但仍有本轮已修复的动态引用发布误判；回退后可能再次拒绝这类报告，不能视作功能等价回退。保留新数据与历史，优先关闭联网开关处理紧急停用。

回退步骤：

1. 关闭模型联网开关，停止接收本次维护范围内的新任务；使用原部署脚本同样的空闲检查，确认 Worker 无活动会话、Python 无活动容器，已开始操作已确认停止或按既有流程保留明确的未知状态。
2. 保存当前 compose/容器配置与镜像身份。仅回退所选服务。Worker 回退使用 R11 的退役记录，API 回退使用 R12 的退役记录，不要混用。将当前容器保留到新的备份名，将对应旧容器恢复为标准服务名并启动；同步 compose 的 image 字段，防止下次 compose 自动切回。
3. 执行 `restrict-coding-worker.sh` 依据实际容器地址恢复出口隔离，校验 internal 网络、IPv6 关闭、非 root、capabilities 和专用回调连通性。
4. 校验 Worker `/readyz`、API/MCP 工具目录、已有报告及历史图表。经明确重新授权后，才对新任务打开联网。

不要把 API 降级到首次集成前的版本并宣称历史仍兼容：旧版本不认识 `internet_search` 资源及 `internetSearch` 合同。若需要降到集成前版本，应先补兼容读取或制定数据迁移方案。新增数据库表保留，不用旧 dump 覆盖发布后生成的会话、任务或索引。只有确认数据库损坏并取得维护授权后才进行数据库恢复。

本轮未实际执行生产回退；回退目标、备份与镜像已保留和核验，不能把预备方案说成真实回退演练成功。
