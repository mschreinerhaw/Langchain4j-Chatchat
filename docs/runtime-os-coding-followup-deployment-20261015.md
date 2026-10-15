# Runtime OS Coding 后续修复与部署记录

日期：2026-10-15，Asia/Shanghai。依据提交 `6b14c2cd` 的受控补给设计和此前 D1 联机记录继续修复。环境为 `192.168.195.221`，SSH 5221，Rocky Linux 8.10 / Linux x86_64，运行 Java 17 与 Node 24。

**本轮修复版本已部署到生产 API 8080 和 MCP 8090；没有完成全部剩余工作。** 真实模型、Python、独立验证、六项证据及异步事件重放已验证。历史 provider 超时仍无法取得可信回执，D1 整体未通过，生产 Coding 未启用。最新设计中的动态补给 v2、D2/D3 接线仍未实现。

## 修复内容与 Runtime OS 边界

- Coding 回调改为正文绑定签名，拒绝旧签名、正文篡改、重放和超过 1 MiB 的正文。Java 使用有界读取并把相同原始正文交给控制器。签名头版本 2 与任务合同版本 v1 相互独立；其他内部调用继续使用原签名方法。
- 模型返回后先保存真实 provider 回执，再检查取消与 fencing 状态。迟到响应不再因为取消竞争而丢失；回执归档不会发布任务结果或授权再次执行。实际没有收到的 provider 响应仍不能凭空生成。
- Node 的模型/工具进度通过现有 A2 checkpoint 事务持久化，以 fence + sequence 幂等去重，冲突内容拒绝。它们作为 `Worker observation` 记录，不提升为执行、计费或业务验证事实。
- 修复生产 `CodingInvocationContext` 的异步订阅：认证线程中捕获同一 tenant/user/run 的 READ 权限，后台订阅只使用该受限读身份；WORKER、SUBMIT 等权限不传出，也不能跨租户读取。
- 为代码启动持久化 `startSequence`，最后一次操作的判断不再依赖最近 128 条事件。旧日志没有启动顺序且相关事件已被裁剪时，保守拒绝自动选择执行结果。
- Node 明确拒绝未协商的 v2 或缺少版本的任务，避免把未来补给协议静默当作固定输入 v1 执行。
- 新增专用 Coding 回调 Nginx 网关和宿主防火墙脚本。仅开放 `172.30.91.1:18081` 的已枚举回调路径；Coding 网段到其他宿主端口和转发网络的流量拒绝。Compose 禁用 Node DNS 和 IPv6，仍保持非 root、只读根目录、无 Docker Socket。

## 验证

| 验证 | 结果与范围 |
| --- | --- |
| Node，本机 Node 24 | 13/13 通过；SDK 修复循环、协议拒绝、签名、HTTP 幂等与 fencing。 |
| Node，服务器实际 Linux 镜像 | 13/13 通过；测试容器无网络、只读根目录。 |
| Java Coding 定向测试 | 43/43 通过，无失败、错误或跳过。包含 29 项 JDBC 持久化/竞争/恢复测试；新增进度幂等、异步真实身份、跨租户拒绝及事件裁剪后的执行顺序验证。 |
| MCP Python 定向测试 | 9 项中 7 项通过、2 项真实 Docker 条件测试在本机跳过，无失败或错误；服务器真实 Python 执行另见下面的联调。 |
| Maven Reactor package | 17 个模块 BUILD SUCCESS，Java target 17。复用现有前端 dist，本轮没有前端源码修改。 |
| 网络边界 | Java 回调无签名返回 401；通用管理 API 路径返回 404；Node 到 8080/8090/18080、MySQL、PostgreSQL、OpenSearch、外网 TCP 均不可达。Node 重启后再次实测通过。 |

构建使用任务专属 `target/coding-maven-settings.xml` 绕过本机失效代理，依赖补齐后离线构建。未改动用户全局 Maven 配置。主要本地日志：`target/coding-followup-package.log`、`target/coding-followup-mcp-tests.log`。

## 真实模型与 Python 联调

复用原隔离 H2 库和已授权的 `0.10 + 0.20` 夹具，模型为现有加密配置的 `qwen3.5-plus`。Java 仅监听 `127.0.0.1:18080`，Node 映射宿主 `127.0.0.1:8792`，MCP 使用实际 8090 控制面。没有向生产 API 提交 Coding 任务，也未修改生产默认模型。

| 场景 | 实际结果 |
| --- | --- |
| 正常计算，部署前受控验证 | `d1-smoke-0bd9f8a5c06b469a879460739b963d3b`：COMPLETED / PASSED，revision 22；模型 2 次，代码 1 次；六项引用齐全，结果 1 行；6 条 worker 进度持久化，从 cursor 12 重放精确后缀通过。 |
| 部署后的故障注入与修复 | `d1-smoke-facf46112a204d19a7f4d279bc18a3ea`：COMPLETED / PASSED，revision 35；模型 3 次，代码 2 次；MCP 中确有 FAILED 和 SUCCEEDED 两次操作；六项引用齐全，结果 1 行；10 条 worker 进度持久化，从 cursor 19 重放精确后缀通过。 |
| 重新核对历史 provider 超时 | `d1-smoke-529585c8ee094b6a811b4c05bc1a6044`：恢复前 RUNNING / revision 6 / unresolved 1；恢复后 RUNNING / revision 7 / reconciliationRequired=true。没有重放模型请求，也未伪造终态。 |

正常任务 bundle：`runtime-evidence:coding:f676146ae47b5c551a67aa40366a009185286b5c489432ca08b61192213ec345:bundle`。

故障修复任务 bundle：`runtime-evidence:coding:129934c34550c18bde982883805ef94e5d6567b5912dfdf3b218a011ccf4dffb:bundle`。

异步事件验证通过真实 `CodingAgentSPI.subscribe` 在请求身份释放后读取、关闭，再以持久游标新建订阅；本轮没有新增浏览器 SSE 端点或声明外部 SSE 网络断线已验收。

隔离启动时默认聊天全文存储曾连接 OpenSearch 并返回 401。仅为隔离实例增加 `chatchat.chat.detail-store.external-text.enabled=false`，保留 Lucene/H2；该失败没有启动模型或 Coding 任务，生产全文存储配置未改变。

## 部署与回滚

发布目录：`/opt/runtime-os-release-20261015`。两个 JAR 均经本地与服务器 SHA-256 核对，再备份、替换、重启。MCP 原进程未在现有停止脚本的等待期限内退出，该脚本执行强制停止；随后新实例正常启动。API 正常停止并启动。MCP 发布流水线记录 19 个 contributor 全部成功；部署后两个管理员登录均为 HTTP 200、业务 code 200 且返回 token。

| 产物 | SHA-256 |
| --- | --- |
| `/opt/chatchat-1.0.0-SNAPSHOT/lib/app/chatchat.jar` | `6ee139f266f758e32c1644d4ee7d77059c813f1fff4b1fe85839fd4344cee576` |
| `/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/app/chatchat-mcp-server.jar` | `947ba87898a0eb4772a945419507a7fc2a17eea92bc2077468c0a4c0f363b954` |
| Node 镜像 `runtime-coding-agent:os-followup-20261015` | `sha256:66041cf4bb872ed37b1d31e95bee432d43d7227a6093ed75c17a8a81bf495c96` |

Node 镜像复用服务器现有、已安装锁定 SDK 的 `runtime-coding-agent:d1-errors` 基础镜像并替换源码/测试，未依赖远端 Docker Registry。旧 Node 容器保留为 `coding-agent-d1-before-followup`，原密钥挂载源已恢复，便于回退。

旧生产 JAR 位于 `/opt/runtime-os-release-20261015/backup/api-before.jar` 与 `mcp-before.jar`。恢复对应 JAR 后分别调用既有 `bin/restart.sh`；不要把隔离 H2 库或测试身份复制到生产数据库。

网络入口及防火墙由 `/opt/runtime-coding-gateway` 和 `runtime-coding-boundary.service` 管理。本轮网关指向隔离 Java 18080。规则只匹配 Coding 网段，生产其他服务网段未改。宿主 Docker/防火墙重启、升级及节点扩容未在本轮执行，之后需重新验证边界。

服务器日志：发布目录中的 `smoke-normal.log`、`smoke-repair.log`、`recover-provider-timeout.log`、`network-verification.log`、`deployment.log`。临时 Java 验证进程已停止；隔离 Node 和专用网关保留供后续核对。密码、共享密钥、模型 API key 未写入此记录。

## 尚未完成

1. 历史 provider 超时任务没有可信执行/停止/计费回执。必须取得 provider 可审计事实，或接入验证这些事实的人工核对流程，才能安全收敛；当前预算预留继续保留。
2. 最新设计的 v2 合同、补数账本、不可变输入版本集、Java 父工作流补给、Node checkpoint/continue 和分析循环接线尚未实现。签名版本升级与进度持久化不等于实现动态补给。
3. D2/D3、浏览器外部 SSE 断线验收、Node SDK 上下文完整持久化以及元数据/产物读取回调仍未闭合。生产 Coding 继续关闭，D1/G3 不标记通过。
