# P3.3.1 第一阶段真实证据

基线 `32e08b765`，采集 `2026-11-08T07:21:37Z`。结论是 **最小原型完成，生产安全未通过，等待第一阶段评审**。本目录不宣称 Python 路径迁移、执行节点崩溃恢复或多 Authority 已实现。

| 原始证据 | 验证内容 |
|---|---|
| [API 与隔离矩阵](runs/live-results.json) | 20 项，19 PASS；CPU/内存/PID 硬限制保留 FAIL |
| [API 汇总](runs/api-summary.json) | capability/隔离检查通过；productionReady=false |
| [实际 Gateway inspect](docker/cline-p331-gateway.json) | UID 1000、无 Socket、受限挂载、cap-drop ALL |
| [实际 Executor inspect](docker/cline-p331-executor.json) | 仅挂独立 rootless Socket；仍为专用 UID 域可信管理组件 |
| [rootless daemon 信息](runs/rootless-info.json) | daemon ID、rootless SecurityOptions、CgroupDriver=none 与未支持的资源标记 |
| [账号映射](runs/rootless-account.json) | 专用 host UID 1004 和 subordinate UID/GID；未使用宿主 root 身份 |
| [启动 guard/namespace](runs/startup-guards.json) | 严格资源模式实际拒绝、错误 daemon 拒绝、Gateway 网络 inode 不等于宿主 |
| [宿主 root 读取探针](runs/rootless-host-root-probe.log) | 在私有 daemon 做受控 privileged rootless 探针，UID 0 仍无法读 root 的 0600 canary |
| [canary 状态](host-root-canary.json) | 宿主实际 UID、权限和内容 SHA256，无真实生产敏感文件读取 |
| [原生模型汇总](runs/native-summary.json) | qwen3.7-plus，7/7 PASS，实际 Worker/CID |
| [原生工具事件](runs/native-events.json.gz) | 官方 read_files、run_commands 原回执；JSON 事件导出不冒充 SSE 全量复验 |
| [原生任务检查](runs/native-results.json) | Gateway→Executor→官方 Cline，项目 Diff 为空，真实停止状态 |
| [原运行时校验](source/official-runtime-verification.json) | 1850 文件、ProtoBus descriptor、Node ABI、SQLite 实际校验通过 |
| [已部署源码 Hash](source/deployed-src.json) | 当前 Executor 镜像的实际 src 字节 Hash |
| [旧服务前快照](runs/shared-before.json) / [后快照](runs/shared-after.json) | ID、镜像、StartedAt、排序后的挂载完全相同 |
| [执行代理审计](executor-audit.jsonl) | 固定动作、拒绝、真实容器创建与停止记录；不含认证凭据 |
| [创建失败](runs/initial-create-failure.json) | 首次真实 503，保留，不用模拟成功覆盖 |
| [首次原生路径失败](runs/initial-native-path-failure/native-results.json) | 请求了不存在的旧目录，原生 read_files 如实失败；后续改为实际 README 路径 |
| [旧快照时序比较](runs/initial-ordering-failure.json) | 原始失败记录保留；后续按 mount Destination 比较 |
| [关闭配额重测](runs/initial-closed-quota-run.json) | 历史测试在补测前停于已关闭 created 容器的配额问题；最新矩阵含清理后不得重建的验证 |

`docker/workers/` 与 [worker-states.json](worker-states.json) 保留实际容器状态，所有留存 Worker 均不在运行。`workers/` 含每个真实模型任务的 registry、原生事件和项目前后 Hash。[retained-directories.json](retained-directories.json) 保留 3 个不匹配现存容器的目录，包括首次准备失败和已关闭后清理的目录；不自动覆盖，不将其当作工具 no-start 证明。

`logs/`、`runs/*log` 保存实际构建、daemon、guard、API/原生任务日志。[SHA256SUMS.json](SHA256SUMS.json) 覆盖采集文件与交付补充文件；使用本目录属性禁用 Git 换行转换，保留原始字节。

完整结论：[第一阶段报告](../../cline-server-p331-20261108.md)、[安全与恢复审查](../../../audits/cline-server-p331-review.md)、[威胁模型](../../../design/cline-server-p331-executor.md)。
