# Phase 3.3.1 权限、崩溃窗口及多 Authority 审查

基线 `32e08b765`，分支 `feature/runtime-cline-cli-native-20261107`。本轮只完成任务书第一阶段：审查、Restricted Executor 最小原型及 Gateway 无 Socket 实证；第二、三、四阶段不实施。第二阶段的原始 checkpoint、45 个持久 Operation、停止回执与 Hash 仍由既有证据测试核验，没有重写或替换。

| 路径 / 调用点 | 基线权限与风险 | 本轮处理 |
|---|---|---|
| `governance-server.mjs` / `allocate` | Gateway 构造完整 Docker spec，经 `DockerEngine.create/call(start)` 操作宿主 daemon | 新配置下改走固定 Executor create/start；旧部署模式保留，原有服务不改 |
| Gateway / `stop`、启动恢复、monitor | 直接 inspect/stop Docker，依赖宿主 Socket | 新配置统一使用 Worker ID＋签名合同的受限查询/停止接口 |
| `docker-engine.mjs` / `call` | 通用 Docker API 能力；持有宿主 rootful Socket 就是高权限可信组件 | 新原型中由 Executor 内部固定动作使用，旧部署路径保留；新增 Docker 错误诊断字段，没有公开 raw API 转发 |
| `PythonDockerRuntime` / createManaged、startManagedDetached、stopManaged、removeManaged、logsManaged | 通过 `ProcessBuilder` 执行 Docker CLI；现有 authority 挂载 rootful Socket | **仅审查，未迁移**。该高权限路径仍是生产阻断项，不能因新 Gateway 无 Socket 宣称全链路降权 |
| `PythonRuntimeProperties.dockerCommand` | 命令路径是单个可执行文件参数；不能把带参数的任意命令字符串当可靠适配 | 后续应提供固定 Python profile 的执行端口适配，继续使用 Managed 状态机/回执，不重写 Python 执行器 |
| `worker-executor.mjs` | 新服务只接受固定生命周期动作及严格字段 | 绑定独立 rootless daemon 的 ID；拒绝非 rootless 或错误 daemon；专用 UID 管理域内仍是可信管理组件 |

Docker Socket 从 Gateway 移走，只能证明 Gateway 不再直接拥有该入口，不能证明 Executor 内部没有权限。本原型的 Executor 仍拥有**专用 rootless daemon 的完整管理权限**；公开给 Gateway 的 API 受限，但 Executor 进程被攻陷后的能力仍覆盖整个专用 UID 域。没有独立的 Docker authorization plugin，也没有宣称 Executor 内部是无权限的通用代理。真正额外的宿主边界来自新 daemon 的非 root 身份和 user namespace；rootful Socket 未挂载到新 Executor。

独立 rootless、专用 VM 与 rootful 专用 daemon 的比较：

| 方案 | 宿主权限边界 | 运维/资源 | Python Managed 兼容性 | 本轮结论 |
|---|---|---|---|---|
| rootless daemon | daemon 和容器落在专用普通 UID 域，共享宿主内核 | 新账号、subuid/subgid、私有存储；本机 vfs 有额外磁盘成本 | CLI 调用语义大部分可复用；UID 映射、目录和资源委派必须重新验收 | 已做实际最小原型；当前无 cgroup 硬限制，不能生产使用 |
| 专用执行 VM，内部 rootless＋cgroup v2 | 增加独立内核/VM 边界，可限制 Executor 被攻陷后的影响面 | VM 生命周期、补丁、配额和镜像运维成本较高 | 保留既有 Managed 控制协议，另验私有卷及镜像 | 推荐生产方向，本轮未部署 VM |
| 受限 API＋专用 rootful daemon | 隔离对象和数据目录，不自动隔离宿主 root 权限 | 较易兼容原 Docker CLI | 较高 | 仍应标为宿主高权限可信服务，不能当作风险已消除 |

rootless 运行方式和 UID 映射依据 [Docker 官方说明](https://docs.docker.com/engine/security/rootless/)。资源限制需单独验证；官方明确说明 rootless 的 cgroup 参数需要 cgroup v2/systemd，`Cgroup Driver=none` 时不能依靠这些参数限制资源，见 [官方资源限制说明](https://docs.docker.com/engine/security/rootless/tips/#limiting-resources)。本机实测 `MemoryLimit/CpuCfsQuota/PidsLimit=false`，报告保留 FAIL，不将 ulimit 当作等价替代。

崩溃窗口审查（仅审查）：

| 源码调用点 | 已有机制 | 未通过项与后续要求 |
|---|---|---|
| `stage2-remote.mjs` / execute(long_mcp) | 先持久 PENDING，再 spawn，随后记录 PID/startTicks；实际进程退出查 result | spawn 与 PID 保存之间仍存在窗口。无 PID 不证明 no-start；需持久执行 Handle/节点身份及容器或监督器登记。保持 UNKNOWN，禁止重派 |
| 同文件 / save、receipt、cancel | fsync 文件与目录，原生停止/实际 no-start tombstone，回执不可变 | 当前是单节点文件执行索引，不具备多执行节点共享原子 admission；进程组/孤儿恢复全窗口未验收 |
| `PythonManagedExecutionService.execute/startCreated` | 现有 SQL ACCEPTED→STARTING，稳定容器名；锁住 task fence 后 Docker start；Docker 查询和回执对账 | Docker start 与数据库 RUNNING 更新不是同一事务。需分别 kill 注入核验；继续复用现有实现，不能另起 Journal 或从数据库回滚推断未执行 |
| 新 Executor / prepare、create、freeze | 固定 Docker 名称、不可变请求 Hash、归属 HMAC、关闭标记阻止迟到 start | 这是容器生命周期原型，不是执行节点恢复整改。准备目录存在但无可确认容器时返回 UNKNOWN；不自动重建或重用。完整故障窗口待下一阶段授权 |

多 Authority 审查（仅审查）：

| 调用点 | 实际问题 | 后续评审建议 |
|---|---|---|
| `NativeOperationAuthority.lease` | owner 写死为 `native-reconciler`，且未过期时返回数据库现有 lease，没有绑定当前副本身份 | 不同副本不能共享 owner 身份或“借用”另一个副本的 lease；需实例身份与显式接管规则 |
| `active`、`dispatch`、`remoteIdentity` | 已校验 Epoch/Fence，但在校验后到外部请求到达之间仍有时间窗口；执行节点只知道自己见过的 Fence | 必须证明旧副本迟到派发会被执行节点拒绝，不能只验证本地 CAS |
| `CodingTaskRevisions` / `DurableCodingTaskService.startIntent` | 既有事务/CAS 可以保证同一 start intent 只被第一次创建；不等于已完成多副本所有权与网络分区验收 | 继续复用既有 PostgreSQL Journal；按任务书 A/B 竞争、接管、分区、并发取消逐一实测 |
| Executor `serial` / Docker 唯一名称 | 仅单进程有界队列和 daemon 名称冲突约束 | 本轮不宣称支持多 Executor/多 Authority；不得把原型局部去重作为分布式副作用唯一性证明 |

额外信任边界：当前合同沿用基线 HMAC，Gateway 同时持有校验密钥，属于任务签发信任域；本轮的参数限制能约束其 Docker 请求，但不能宣称 Gateway 被攻陷后仍无法伪造合同。生产方案应由独立签发方持有私钥，Gateway/Executor 只持验证公钥；此处只提出方案，不扩展本轮认证架构。

结论：最小原型的能力边界可以实证；完整 Docker 权限整改、资源隔离、Python 路径迁移、崩溃恢复和多 Authority 均不能宣布完成。停止在第一阶段评审点。
