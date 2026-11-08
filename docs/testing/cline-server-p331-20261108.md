# Phase 3.3.1 第一阶段评审报告

基线 `32e08b765`。**Docker 权限拆分审查与最小原型完成，停在第一阶段评审点；完整安全验收未通过。** Gateway 无 Socket、受限生命周期 API 和独立 rootless UID 边界已实证；资源硬限制未通过，既有 Python rootful 路径尚未迁移。不能宣称宿主权限风险全部消除，不能进入下一阶段或用于生产。

交付：[原始证据与 Hash](evidence/cline-server-p331-20261108/README.md)、[权限/崩溃窗口/多 Authority 审查](../audits/cline-server-p331-review.md)、[Executor 设计及威胁模型](../design/cline-server-p331-executor.md)、[部署与回滚](../../cline-agent-server/docker/README.p331.md)。

| 真实检查 | 结果与证据 |
|---|---|
| Gateway 无 Docker Socket | UID 1000；mounts 无 rootful/rootless Socket；实际连接报 ENOENT |
| 不提供任意 Docker API | 镜像、挂载、privileged、host network/PID、devices、Cmd、资源字段和 raw API 路径注入被拒绝；逐项对比 daemon 容器清单无新增 |
| 服务端固定容器参数 | 实际 inspect 的镜像为基线摘要、UID 1000、network none、rootfs readonly、cap-drop ALL、仅固定目录挂载 |
| 归属与生命周期 | 独立合同校验、跨归属拒绝、相同请求同 CID、参数冲突、独立配额、stop/kill/cleanup 与关闭后 start 拒绝均通过 |
| OS/挂载边界 | 实际 Worker 无控制面配置、无 Socket、CapEff=0；Gateway 无权 rename 挂载根；损坏 symlink 锚点经实际 API 拒绝，Docker 未 start |
| rootless 宿主身份 | 专用账号 `cline-p331`，daemon `SecurityOptions=rootless`；user namespace 的 root 映射宿主 UID 1004；受控 privileged rootless 探针仍无法读取宿主 root 的 0600 canary |
| 网络 namespace | Gateway 的 host 模式处于 RootlessKit 私有网络 namespace，inode 与宿主不同；未暴露 Docker TCP |
| 原生 Cline / 真实模型 | `qwen3.7-plus` 经 Gateway→Executor 创建官方 v4.1.26 Worker，自主完成原生 read_files 和 run_commands；项目 Diff 为空，随后取得实际 Docker 停止证明，7/7 检查通过 |
| CPU/内存/PID 硬限制 | **FAIL**：本机 cgroup v1，rootless `CgroupDriver=none`、三个能力标记均 false；评审模式显式返回 resourceEnforced=false，严格模式实际拒绝启动 |
| 既有服务未改 | 旧 Gateway/authority/MCP 的 ID、镜像、StartedAt、挂载快照前后一致；没有重启共享 daemon 或变更其配置 |

API/隔离矩阵共 20 项：19 项通过，资源硬限制 1 项保留 FAIL（包含容器清理后换 Worker ID 也不得重新创建同一关闭任务）。另有 3 项真实启动/namespace guard 通过；Node 独立测试包括原基线证据完整性检查。资源 FAIL 不会因为测试框架对“预期拒绝”检查通过而被改成安全通过。

本轮实际失败及修复：

1. 初次 rootless 启动的 PATH 缺少 `/usr/sbin`，RootlessKit 无法调用 `ip`；修复仅限新进程环境，daemon 原始日志保留。初次 Docker info 格式化命令在连不上 daemon 时也可能返回空 ID 的 JSON，已要求非空 ID 和 SecurityOptions，禁止据此误认健康。
2. 第一次 Worker 准备目录过早转交给 UID 1000，管理进程仅有 CHOWN、没有 DAC_OVERRIDE，真实 create 返回 503。改为完成复制后按后序交接叶目录，固定父目录继续由 Executor UID 0 持有；未通过扩大 Gateway 权限解决。
3. 已关闭但从未启动的 created 容器仍占用配额，导致重复测试被 429 拒绝。现在只释放“实际 created 且有 Executor-owned close 标记”的槽；running/paused/restarting 始终占用，关闭标记仍阻止迟到启动。
4. 首次共享快照比较受 mounts 返回顺序影响，保留初始失败，后续按 Destination 排序再比较一次快照，未改服务。
5. 首次原生任务使用了旧测试目录 `coding-agent-node/README.md`，当前工作区实际 README 在根目录。原生 read_files 如实返回失败并保留原回执；修正测试请求为实际项目路径后复测通过，没有补造文件或工具结果。

本轮未做：Python 管理链迁移、执行节点 spawn/PID 窗口恢复、多 Authority/网络分区、daemon 重启、破坏性资源耗尽或容器漏洞利用。这些仅做源码审查和后续方案，不将本轮的容器生命周期去重包装成完整分布式操作恢复。

第一阶段遗留阻断项：提供支持 cgroup v2/systemd 资源委派的独立执行环境（优先专用 VM 内 rootless），再验证真实限额；迁移 Python rootful 路径；分离合同签发/验证角色；评审 Executor 在专用 UID 域内仍拥有的完整管理权限。现有宿主 cgroup、systemd、生产 Docker 与模型配置均未改。完成证据审查前，不实施 Phase 3.3.2/3.3.3/3.3.4，不切换默认 Coding Agent。
