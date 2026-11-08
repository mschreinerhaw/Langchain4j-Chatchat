# Restricted Executor 最小原型与威胁模型

目标是让 Gateway 只能请求受控的 Worker 生命周期，而不能提交 Docker 参数。原型不参与 Agent 决策、不执行模型选择、不重新实现原生工具，也不新增 Operation Journal。

```text
HTTP client → Gateway (UID 1000, no Docker socket)
                    │ authenticated UDS + signed task
                    ▼
        Restricted Executor (root in dedicated user namespace)
                    │ fixed server-generated Docker calls
                    ▼
        Dedicated rootless daemon (host UID cline-p331)
                    │
        Cline Worker: pinned v4.1.26 image, UID 1000, network none
```

唯一内部 API 是创建、start、GET 状态、stop、固定 SIGKILL、health、删除已确认关闭的 Worker。没有 Docker URL 转发、exec、build、pull、prune、任意镜像、任意网络或任意文件操作 API。请求必须通过服务凭据和独立合同校验；允许字段只有 `workerId/resourceProfile/apiToken/brokerToken/editTool`。合同来自 Gateway 当前已验证任务，租户、用户、工作区、模型名称和工具授权再次按 Executor 自己的只读策略核验。

所有镜像、用户、工作目录、网络、挂载根、capabilities、security options 和资源规格由服务策略生成。资源 profile 固定为 `standard`。**当前宿主缺少 rootless cgroup 支持，只能在显式 `allowUnenforcedPrototype=true` 的评审模式运行**；返回 `resourceEnforced=false/productionReady=false`。默认严格模式直接拒绝启动，不能用原型模式上线。

| 威胁主体 / 请求 | 边界与验证 |
|---|---|
| 普通租户调用内部 API | 无 Executor Socket 挂载、无服务凭据；公开 Gateway 继续执行任务签名与归属检查 |
| Gateway 提交任意 Docker 参数 | 精确字段白名单，未知字段拒绝；即便服务凭据正确也不能注入镜像、挂载、特权或 namespace |
| 任意 Docker API 路径 | 只有固定 `/internal/workers` capability 路由，无通用转发；测试同步检查 daemon 未新增容器 |
| 更改已创建请求、替换 Worker ID | 固定任务派生 Docker 名称、归属 HMAC、完整合同 Hash、规范化请求 Hash；重放返回同一实际容器，不同参数冲突 |
| 跨租户/跨任务控制 | 每次按签名合同推导目标容器，并校验 Executor 实例、合同、Worker ID 和归属标签；不接受客户端 Container ID |
| symlink 或 mount 目录替换 | 模板只读且递归拒绝 symlink；挂载根及 Worker 父目录由 Executor UID 0 持有，Gateway UID 1000 无 rename 权限；动作前再次检查锚点 |
| 关闭后的迟到 start | Executor-owned 关闭标记先持久化，再停止；任务归属关闭标记保留至容器清理后，换 Worker ID 也不能重新创建；停止回执取自实际 Docker 状态 |
| Executor 被攻陷 | 仍掌握私有 rootless daemon，全 UID 域内可信管理权限；API 白名单无法防御其自身被攻陷，宿主非 root UID 和 namespace 提供额外边界；共享内核风险仍在 |
| 资源耗尽 | API body 32 KB、变更队列 32、独立 Worker/租户并发上限；当前无容器级 CPU/内存/PID cgroup 限制，明确未通过，不执行破坏性耗尽实验 |

挂载目录不由 Gateway 自己创建后直接交给 Docker。Executor 先在私有挂载根建立 UID 0 的固定父目录，完成复制后按后序把 workspace/state/bridge 交给 Worker UID 1000，配置文件是 `0:1000 / 0440`。Gateway 只写独立 registry 目录和授权 bridge/工作目录，不能替换挂载根。准备失败留下的未知目录不自动覆盖，以免把目录不存在/容器查询失败误当作 no-start。

Gateway、Executor 和 Worker 都运行于新 rootless daemon；Gateway 的 `network=host` 仅表示该 daemon 所在 RootlessKit 网络 namespace，已实测与宿主网络 inode 不同。Worker 始终 `network=none`。Gateway 只挂载其配置、registry、Worker 根目录和受限 Executor UDS，不挂载任何 Docker Socket；daemon 不开放 TCP Docker API。Executor 只挂私有 rootless Socket，没有 `/var/run/docker.sock`。

Python Managed Execution 暂保留既有服务及 rootful Socket，本原型不授予 MCP/Python 工具，也不以新 Executor 替代 Java 工具执行器。后续授权后，应增加受控 Python profile/执行端口适配，复用既有 Managed 状态机、输入快照、容器身份和回执；先在支持 cgroup v2/systemd 委派的专属执行环境解决资源硬限制。

代码差异：新增 `worker-executor-policy.mjs`、`worker-executor.mjs`、`worker-executor-client.mjs`；Gateway 加可选受限路径；合同校验新增仅供观察/停止/清理的过期合同选项，公开 Gateway 默认规则不变；Docker helper 仅增加原始错误诊断。官方 Cline bundle、Agent Core、工具执行器、Java Journal、Managed Python 和现有共享服务均未修改。
