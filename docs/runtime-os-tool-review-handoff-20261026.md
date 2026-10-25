# Runtime OS 性能修复：当前状态与剩余工作

记录日期：2026-10-26，Asia/Shanghai。按用户本次要求整理并提交剩余工作，暂不继续追加发布。

## 一、当前生产状态

- 分支：`fix/runtime-os-tool-review-performance`，工作仓库为当前工作目录下一层的 `Langchain4j-Chatchat`。
- **已部署的功能源码为 `e6eba0291`，不是最新分支 HEAD。** Git 同步、原有合并冲突处理、稳定供数修复和规范资产引用边界补强已在首轮发布中上线。
- Live Monitor 生产发布任务：`df1257ff-a4d3-489a-acd0-0f9dd461fe2a`，最终 `SUCCEEDED`。
- 已完成停写备份，校验 **19,505 个文件**；7 个应用服务通过健康检查，41 个非目标容器的镜像和启动时间未改变。
- 维护模式已关闭。本次交接前再次查询：7 个应用服务运行，Coding Agent 健康，没有运行中的候选镜像测试容器。
- 授权和模板绑定已用本次部署的数据库备份与当前数据逐行比较：`mcp_tool_permission`、`resource_grant`、`sys_user_role`、`sys_role_permission`、`mcp_template_query_binding` 均未改变。
- 原始未提交修改及未完成合并状态仍备份在本地父目录 `runtime-review-backup-20261025-225310`；没有执行 hard reset。

当前发布包：

```text
/opt/live-monitor-managed-releases/release-20261025-tool-review-e6eba0291
package SHA256: 2b208620edbc89a1a385c05fec0f81d07e9bb09605a95815d4d1e9ef0805d53d

API image:
localhost:18443/runtime-os/api@sha256:f1ca714d81d6fbc3820102b006721ec7ae8239766688fe973e9d53b0e8b10704
API JAR:
854026c00480746cf44f6218304814284419dcb44e1afaf5bf3cb7044e68e699

MCP image:
localhost:18443/runtime-os/mcp@sha256:9c551fbba1a097b14bf1ba720149e66a9515fb2908b9990f8ce6064626d46890
MCP JAR:
10768bd17834981f4e2a59050ebe72754f672e863c60bf602625947eb30d8766
```

备份目录为 `/opt/live-monitor-managed-releases/agent-production/backups/stack-df1257ff-a4d3-489a-acd0-0f9dd461fe2a`。首轮发布目录中保留了部署任务、权限比较、实际镜像/JAR 核验和只读资产恢复的证据。

## 二、已经完成的真实验证

### 原问题完整链路

- 任务：`faba8116-ed7e-3441-9397-32b8e12161f1`。
- Run：`att-1-649f0ee3-f6a2-4aba-8957-878d0c2f306b`。
- 复用原问题、`server_ops_assistant` 和原绑定 `qwen3.7-plus`，没有通过切换高速模型获得结果。
- 最终 `SUCCEEDED`，Coding 完成，Python 实际执行，生成可用报告，归档代码及事件可查询。
- Runtime 未再次执行完整语义分析，`runtimeSemanticReviews=0`。
- 一次 `REQUIRED_OUTPUT_MISSING` 得到受控修复。后续成功程序还有一次改写；不能仅凭“再次执行”就认定语义修复没有必要。

### 知识问答

- 任务：`f2d5e3fa-f3ed-33c9-a956-4aa013385c91`，最终 `SUCCEEDED`。
- 没有执行 MCP 数据采集，但仍走 Cline 上下文回答和 Python 报告序列化。
- **知识问答功能可用，不等于“简单问答不进入完整 Coding Loop”的性能目标已经达成。**

### 文件分析与隔离

- 任务：`2a0e8e56-5cee-34bf-a38b-8f7bbe5f02a4`，最终 `SUCCEEDED`。
- 真实上传 CSV，经对象存储、授权输入快照及 Python 计算，核对 Decimal 合计 `153.17`。
- 匿名、跨用户附件、重复附件、未发布 Agent、受限 API token 和不支持文件类型等拒绝用例通过；临时账号、角色和 API token 已清理/撤销。
- 14 个生成归档逐个下载并验证大小和 SHA256，代码归档可读。
- 验收脚本最初因默认 `personalOnly=true` 漏查生成文件，导致两项归档检查失败。已在 `514fd4299` 修正为 `personalOnly=false` 并联机复核通过；原失败记录与复核记录均保留，不能隐藏初次失败。

## 三、真实性能基线及其限制

| 指标 | 首轮上线后的原问题 | 知识问答 |
|---|---:|---:|
| 官方任务端到端耗时 | 477,107 ms | 91,488 ms |
| Java 模型调用 | 4 | 0 |
| Java 模型耗时合计 | 111,226 ms | 0 |
| Coding 模型调用 | 6 | 3 |
| Coding 模型耗时合计 | 271,777 ms | 71,100 ms |
| Coding 实际输入 token | 226,713 | 53,715 |
| Coding 实际输出 token | 16,283 | 4,205 |
| Python 执行次数 | 3 | 1 |
| Python 进程耗时合计 | 4,705 ms | 1,142 ms |
| Runtime 再次完整语义审查 | 0 | 0 |

原问题的受控模板批次耗时 21,300 ms。必要的资产语义审查本次约 18 秒，后续固定供数没有逐模板重复做模型审查。

此前 `715e09f92` 的故障样本总耗时 153,329 ms，其中 Review 在 60,012 ms 超时，最终失败且没有进入 Coding/Python。**不能把失败样本与后续成功样本直接计算为端到端加速比例。**

当前样本数量不足，数据采集时刻及快照也不完全相同。尚无合格的同条件多样本 P50/P95/P99，不能宣称 P95 下降 50% 已达标。对照文件分析时可以复用已保留的同一不可变 CSV 输入，其身份在服务器私有记录中。

## 四、已提交但尚未部署的追加优化

功能提交：`48751061b`，`perf(coding): hand verified artifacts directly to the semantic review turn`。

发现原问题的两轮模型调用仅用于请求 `artifact_read` 读取已经确定的结果引用。追加优化复用既有读取机制：

1. 仅对原有 `analysis-reviewed-path:v1` 契约且已授权 `artifact_read` 的任务启用。
2. Python 必须已经停止，并返回可用结果、验证记录与证据引用；STOP、失败或缺失输出不能触发自动读取。
3. Worker 立即调用原有 Java `readArtifact`，核对精确引用、完整性及单报告行，将真实报告放入 `reviewedArtifact`，交给下一轮 Cline 进行语义审查。
4. 引用无效仍需修复；授权失败、错引用或读取失败不能发布结果。保留日志、checkpoint、预算、取消与专业语义修复。

这一步减少纯调度模型往返，**没有自动宣布业务分析正确，也没有跳过 Cline 对实际结果的语义审查**。

相关代码：

- `coding-agent-node/src/runner.js`。
- `coding-agent-node/test/reviewed-result.test.js`、`test/monitor.test.js`。
- `chatchat-api/.../runtime/coding/analysis/CodingAnalysisService.java` 及同名测试。

本地 Node **59 项通过**，Java 定向 **85 项通过**，标准 Maven package `BUILD SUCCESS`。此前综合回归为 Java 379 项中 378 通过、1 项因未配置 `coding.sqlglotPython` 跳过；这些测试集合有重叠，不应相加冒充独立覆盖数量。全仓历史测试未宣称全绿。

## 五、当前发布阻塞：候选 Node 镜像的静态资源权限

候选目录：

```text
/opt/live-monitor-managed-releases/release-20261026-result-review-514fd4299
```

**该候选没有 `prepared-public.json`，没有完成封存登记，没有发起生产部署，不能直接发布。**

- 候选 API 已按标准构建并上传，JAR SHA256 为 `9504eca7dc7bf46a31111b331e4f2f61d489795eb86d3a96a9d3f43be91bfe17`。
- API 候选镜像已推送：`localhost:18443/runtime-os/api@sha256:1f44bb4a83e9b2a52d5a48b9c54ae0b92126e489ce46b2449bb7c6d3ae4a3b9a`。
- Node 候选使用原生产依赖镜像作为固定依赖底座，覆盖的是本仓库完整构建的 `src/dist`，不是从旧容器取源码冒充新版本；已比较依赖声明。
- 第一轮镜像测试缺少 `fixtures`，已补入归档。第二轮发现监控 HTTP 用例失败，测试容器因错误后未关闭连接而未正常退出；该自建测试容器已停止，目前没有残留运行的候选测试容器。
- 已核对根因：准备脚本设置 `umask(077)`，`tarfile.extractall(filter='data')` 提取目录后，候选镜像里的 `dist/public` 为 **root:root 0700**；正式 worker 用户为 **1000:32101**，无法读取监控页面。交接前已实际验证 `test -r dist/public/index.html` 失败。
- 读取失败还暴露现有 `src/monitor.ts` 的错误处理缺陷：先 `writeHead(200)` 再 `readFileSync`，捕获文件错误后再次写 503，产生 `ERR_HTTP_HEADERS_SENT`。

### 优先继续顺序

| 优先级 | 工作 | 验收要求 |
|---|---|---|
| P0 | 修正候选源码/静态资源打包权限 | 在源归档提取后的**公开源码构建上下文**中规范目录 0755、普通公开文件 0644；不要对数据、密钥或运行目录递归放权。生产非 root 用户可读取监控静态文件 |
| P1 | 修复监控缺资源时重复写响应头 | 在发送成功响应头前完成文件读取；新增缺资源返回 503、服务仍可用的回归 |
| P0 | 重建完整 worker 并重跑 Linux Node 24 镜像测试 | 包含 `src/dist/test/fixtures` 及完整 `dist/public`；全部测试通过，无挂起、无测试容器残留；不能只凭 Windows 本地通过发布 |
| P0 | 封存并通过 Live Monitor 发布追加优化 | 核对生产仍是 e6eba0291、API/worker 产物与源码匹配；保留回滚版本。当前候选计划只改变 API 和 Coding Agent，MCP 镜像保持首轮已验证版本 |
| P1 | 联机复测自动结果读取 | 原问题、知识问答、同一 CSV 输入；核对自动读取日志、下一轮模型确实获得真实报告、引用错误受控修复、实际模型轮次/Token/成功率。不能用单元测试替代 |
| P1 | 完成同条件多样本性能比较 | 固定模型、任务、输入快照及硬件；报告端到端、Review、首次有效结果、重复工具调用、Token 和成功率。以实际数据判断 P95 目标 |
| P1 | 简单 QA 轻量路径 | 当前仍需 Cline/Python 序列化；追加自动读取最多减少纯调度轮次，不能据此宣称已经绕过完整 Coding Loop。继续复用既有模型与授权机制，避免另建执行体系 |
| P1 | 跨 requestId 的供数去重 | 现有去重主要基于 requestId/operationId；相同参数换 ID 仍可能再次采集。必须具备模板版本、快照身份、授权变化与有效期信息后才能安全复用，不能直接缓存可变数据源 |
| P2 | 历史测试及监控状态表达 | 旧 `AgentOrchestratorTest`、三项模板发现测试仍有已记录历史失败；父任务在 Coding 活跃时曾显示 PLANNING/WAIT_MODEL，也应单独核对投影，不能据此误判执行未启动 |

继续发布仍应经过已建立的 Live Monitor 备份、空闲检查、健康核验和回滚流程。不要直接重启第二套 Runtime，不要删除旧备份或更换模型来掩盖问题。

## 六、续接材料

### 本地，均在忽略目录

- `target/runtime-review/build-auto-release.py`：候选构建/封存辅助脚本；**尚未加入上述目录权限修复，不能原样重跑后直接发布**。
- `target/runtime-review/auto-artifacts.json`、`node-auto-review-source.tgz`：候选产物身份。
- `target/runtime-review/auto-read-package.log`：85 项 Java 回归及构建记录。
- `target/runtime-review/node-auto-review-tests-final.log`：59 项本地 Node 回归。
- `target/runtime-review/deploy-managed.py`、`platform-plan.py`、`post-deploy.py` 等：首轮操作辅助脚本，路径和任务 ID 针对旧发布，继续前必须核对并适配，不能盲目重跑。
- 所有 `target` 产物可能被再次构建覆盖，继续时重新核对源码提交和 SHA256；不要执行仓库根目录 `mvn clean` 清掉续接材料。

### 服务器

- `/opt/runtime-review-20261026/`：原问题、QA、性能 Trace、模型/执行回执等私有原始证据。
- `attachments/acceptance.json`：原归档检查失败记录；`attachments/acceptance-recheck.json`：修正查询条件后的通过记录。
- `file-original-identity-private.json`：同一 CSV 输入的身份、SHA256 和复测问题。
- `original-trace-public.json`、`qa-trace-public.json`、`performance-public.json`：已脱敏的首轮性能投影。
- 新候选目录中的 `production-node-tests-first-packaging-failed.log`、`production-node-tests.log`：镜像测试失败记录。日志未覆盖前应先归档；下一轮要同时保存通过记录。

登录凭据、原始业务输出、私有会话和完整任务数据不得提交 Git。控制台凭据已经验证可用；会话可能过期，续接时使用用户已提供的凭据重新登录，不要重置生产账户。

本说明提交前，生产保持首轮健康版本，维护模式关闭；追加优化停留在修正候选镜像测试的阶段。
