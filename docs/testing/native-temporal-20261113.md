# Native Cline / Temporal 实施与候选验收

## 基线与范围

开发分支：`feature/runtime-cline-temporal-scaling-20261112`。基线为
`feature/runtime-cline-cli-native-20261107` 的
`9d6a420a39ab7f8070f1c5d6ee4b69878a00c4da`；提交前再次 fetch，远端基线未改变。
使用独立 worktree，未覆盖原工作区已有的静态构建修改。

本次增加官方 Temporal Java SDK 1.37.0 的整任务 Workflow/Activity，以及
Native Worker Adapter、执行 fence、独立受限停止控制、监控关联和部署探针。
官方 Cline 4.1.26 Core/Agent Loop、原生工具、Python 3.11、MCP/Skills 和
共享模型配置保持原实现。没有新增业务分析规划器、报表数值规则或模型调用次数上限。

候选入口为 `http://192.168.195.221:19100/monitor/`，只通过 Java 认证 API 查询。
原业务入口 18100 及原服务保持运行，未将生产任务切换至 Temporal。
部署说明：[NATIVE.md](../../deploy/temporal/NATIVE.md)；
架构审查：[native-temporal-scaling.md](../architecture/native-temporal-scaling.md)。

## 扩容的实际效果

以下为固定源码 `d1d706f72bdb78a95cc254df8aa29344f9f65b50` 构建并重新部署后的最终复验。
两组使用同一候选镜像、相同模型 `qwen3.7-plus`、相同 Parquet SHA-256、相同
用户任务说明和每节点资源配置。两个真实认证的内部测试账户分别上传自己的输入。
单节点组停止第二个 Java 队列消费者；双节点组启用两个消费者，各有一个整任务槽位。
每个 Native Worker 上限 2 CPU、3 GiB、256 PID。没有以容器在线数量作为验收依据。

输入为明确标注的 SYNTHETIC 数据，4 行，amount 合计 40，均值 10。
只上传数据与任务目标，源码、执行、报告和 RESULT 由 Cline 自主生成。
每个任务均独立验算，并从 Java 下载源码、报告和结果，复核大小及 SHA-256。

| 指标 | 单节点 | 双节点 |
| --- | ---: | ---: |
| 任务数 / 成功 / 失败 | 4 / 4 / 0 | 4 / 4 / 0 |
| 总完成时间（秒） | 381.060 | 180.864 |
| 吞吐量（任务/分钟） | 0.629822 | 1.326964 |
| 平均排队（秒） | 142.934 | 42.20025 |
| P95 排队（秒） | 296.991 | 87.848 |
| 平均执行（秒） | 92.5835 | 80.229 |
| P95 执行（秒） | 113.258 | 89.028 |
| 真实模型请求 | 37 | 34 |
| 输入 / 输出 Token | 382791 / 15448 | 305452 / 12178 |
| 用量未提供的请求 | 0 | 0 |
| 实际任务分布 | 节点 1：4 | 节点 1：2，节点 2：2 |

本组测得吞吐提升 **2.10689 倍**。两个不同账户在不同节点的实际 Native 调用区间
分别重叠 68.303 秒和 44.988 秒；两节点真实 Core 内存/CPU 观测也同时非零。
单节点达到槽位上限时，其余任务保持 CREATED/Temporal 排队，随后依次领取。

Core 采样峰值内存：单节点 786.04 MiB；双节点 738.47 / 728.67 MiB。
CPU 保存受限 Executor 返回的原始累计纳秒采样，不伪装成完整任务 CPU 总量；
采样会随 Core 容器更换而重置。每组只有 4 个样本，P95 为最近秩统计，
不构成长时间生产 SLA 或大规模容量保证。不同轮次真实模型推理量也有差异。

详细输入、节点、请求用量、时间区间、文件哈希与采样：
[benchmark.json](evidence/native-temporal-20261113/benchmark.json)。
初轮源码候选测得的 1.85898 倍结果完整保留在
[benchmark-initial.json](evidence/native-temporal-20261113/benchmark-initial.json)，不与最终轮合并统计。

## 故障恢复与唯一有效结果

在节点 2 的真实 Python 进程运行期间，强制终止其 Native Gateway 和 Java
Temporal Worker。确认真实进程与 readiness 文件后才注入故障；不是模拟接口错误。
另一消费者等待旧 journal 租约，调用独立 Executor 控制面持久化撤销并实际停止旧 Core，
随后在节点 1 以新 Session/Epoch 使用相同不可变输入完成分析。

| 标识 | 故障前 | 接管后 |
| --- | --- | --- |
| 节点 | `cline-worker-2` | `cline-worker-1` |
| Session | `native-e976fb54-8295-4cd4-9714-899df6ccd2cb` | `native-b5546a6e-a564-464b-a312-6477ae61e791` |
| executionEpoch | `615ed7ba-53b3-46c7-a5f2-221dbf611b7b` | `55ca02d8-ad70-454b-8cfd-c24483fccb66` |

接管后的业务值为 4 / 40 / 10。恢复旧节点后，实际重放旧消息、旧交付及旧模型请求，
均返回 **403**；旧容器确认不再运行。重放前后的有效 dataset reference、交付文件 ID、
大小和 SHA-256 完全相同。有效最终结果仍只有当前 journal 登记的一份。
旧执行目录中遗留的文件不构成有效交付，不会根据对象 latest 或退出码 0 自动验收。

[接管与重放证据](evidence/native-temporal-20261113/node-recovery.json)、
[重放后独立产物验算](evidence/native-temporal-20261113/node-recovery-final-result.json)。
原始 Session/Worker 日志、停止证明和 Workflow History 保留在服务器候选 evidence。

## 其他真实验收

| 项目 | 结果与判定依据 |
| --- | --- |
| 实际取消 | PASS；最终镜像在另一任务故障重派占用分析槽位时取消真实 Python，5.164 秒内取得 Executor 停止证明；Java CANCELLED、Temporal CANCELED，无有效最终结果 |
| Silo 短暂不可用 | PASS；仅在候选网络 namespace 中拒绝 Silo TCP 连接 40 秒，真实拒绝流量计数 10 个包，规则精确清除 |
| Temporal 重启 | PASS；上述执行期间重启 Temporal，Activity 重试后保持原 Native Session、executionEpoch 和 Workflow Run，最终数值正确 |
| 文档 MCP | PASS；现有 `document_search` 返回 Agent 授权文档，独立核对 Aurora Desk、7 天和随机批准代码；未分配文档 canary 未泄露 |
| Skills | PASS；原生 Read 工具真实读取两个 image Skill 的 SKILL.md 及 data-methods / engineering-checks 引用资源，按需读取回执已保存 |
| Python 自主修复 | PASS；真实 str-plus-int TypeError、源码变更、重新执行成功；动态多文件源码、报告及数值验算通过 |
| 同租户跨账户 | PASS；两组全部任务和产物的另一账户访问均为 403 |
| 跨租户 | PASS；新建第二个测试租户和账户，读取第一租户任务及监控详情均为 403 |
| 监控 | PASS；真实浏览器无页面/API 错误；Workflow、节点、历史时间线可见；时间线/交付默认折叠；离线重连与刷新后历史仍可查询 |
| Temporal mTLS | PASS；带证书的真实 SDK/CLI 连接成功，不提供客户端证书的连接被拒绝 |
| 回归 | PASS；60 个 Java 测试、21 个 Node policy/daemon/issuer/governance 测试，失败 0；包含分析槽位被占满时的控制队列取消及两份真实旧 History 回放 |
| 联网正向检索 | 未执行通过；现有模型策略为 MODEL_INTERNET_DISABLED，保留原授权，未修改共享模型配置来制造成功 |

证据：[最终并发取消](evidence/native-temporal-20261113/control-queue-cancellation.json)、
[实际控制队列及版本标记 History](evidence/native-temporal-20261113/final-control-queue-history.json)、
[初轮取消](evidence/native-temporal-20261113/cancellation.json)、
[存储/重启](evidence/native-temporal-20261113/storage-restart.json)、
[文档](evidence/native-temporal-20261113/document-search.json)、
[Python 修复](evidence/native-temporal-20261113/python-repair.json)、
[Skill 读取](evidence/native-temporal-20261113/native-skill-read-35.json)、
[引用资源读取](evidence/native-temporal-20261113/native-skill-read-56.json)、
[租户隔离](evidence/native-temporal-20261113/tenant-isolation.json)、
[浏览器](evidence/native-temporal-20261113/browser.json)、
[回归统计](evidence/native-temporal-20261113/regression.json)。
页面实拍：[任务列表](evidence/native-temporal-20261113/task-list.png)、
[任务详情](evidence/native-temporal-20261113/task-detail.png)。

存储/重启探针的 elapsed 为复合探针耗时，包含等待防火墙规则移除；没有单独测得
Temporal 启动耗时，不能将该值宣传为 Temporal 重启 RTO。

## 保留的失败与修正

没有把以下独立校准/失败轮次混入成功吞吐量，也没有删除它们的原始记录。

| 轮次/阶段 | 观察与处理 |
| --- | --- |
| 初始普通账户提交 | 403；配置单独的内部验收角色，未放宽 Java 对象权限校验 |
| 初次 Compose 启动 | namespace 模式不允许 extra_hosts；移除 Worker 的该选项 |
| 初次 Temporal 启动 | 非 root 用户无法读挂载脚本/证书；修正公有证书和脚本权限，私钥仍受限 |
| 初次 Java 启动 | 共用 RocksDB LOCK 冲突；改为各进程独立数据目录 |
| 首次 Native Worker | Unix socket 路径 113 字节超过系统限制；取消、确认停止、只清理该已关闭候选容器，改为短路径并添加长度校验 |
| 第二次 smoke | 旧 created 容器占槽，自动重试/租约接管后完成；不计入性能基线 |
| 第一轮长等待故障任务 | 120 秒超过原生 Shell 的前台等待行为，Cline 改用后台等待且未完成最终交付，人工取消；标记失败。后续独立 25 秒前台任务通过，不宣称该长等待案例已修复 |
| 初次进程探针 | Docker top 未输出 PID 列，探针报错；改为 pid,comm 后才进行实际故障注入 |
| 文档回归 1/2/4 | NO_MATCHES，业务验收失败；发现候选 Compose 自动注册 api 别名，与现有共享网络重名。立即移除候选别名，正式脚本改用唯一服务名；通过原文档权威服务重建合成资料后，第 5 轮通过 |
| 文档回归 3 | fixture 未生成，测试初始化失败，没有 Workflow |
| 联网回归 1 | 本机系统代理中断测试连接，无 Workflow；后续显式直连候选入口 |
| 联网回归 2 | 冻结授权明确为 MODEL_INTERNET_DISABLED，无 web 工具；取消该不可满足任务，保留失败，不通过改共享策略掩盖 |
| 浏览器探针 1/2 | 测试选择器误用 a 标签、waitForFunction 触发 CSP；改用实际 button 和 Locator，保留 CSP，最终源码脚本通过 |
| 固定源码首轮并发取消复验 | 进程和 Java 已取消，Temporal 停止回收仍与长分析争抢槽位，瞬时终态断言失败；新增独立 `.control` 标准队列和版本标记。SDK 饱和槽位测试、两份真实旧 History 回放及最终镜像并发取消均通过，最终实测 5.164 秒 |
| 最终验收辅助脚本初始化 | 独立结果脚本首次误用输出环境变量、浏览器首次未指定 worktree 外已有 Playwright 模块路径，均为测试启动错误；修正参数后按原源码脚本重跑通过，未修改产品逻辑或 CSP |

## 发布状态与边界

已从固定 Git 提交 `d1d706f72bdb78a95cc254df8aa29344f9f65b50` 上传源码远程构建镜像，
重新创建独立候选服务，并完成上述最终扩容、故障重派、并发取消、文档 MCP 和浏览器复验。
候选部署按镜像 ID 固定，未替换 18100 生产入口。

| 组件 | 标签 / 固定镜像 ID |
| --- | --- |
| Java API / Temporal Adapter | `runtime-os-api:temporal-native-d1d706f72bdb` / `sha256:4458af182ff49ae526d39092ae0830079c8184c88bfe7fee596e2f8ff4fddb03` |
| Native Node / Executor / Recovery | `runtime-os-coding-node:temporal-native-d1d706f72bdb` / `sha256:173f7368811538659bc122f5608dfd9debaa3647ea7c0cad57357eebad2dd822` |
| 固定 Native Core + Python Worker | `sha256:60641b46986f7b260de9e89c491f9bb956ca0322038dbbc034683390a448953a` |

官方 Cline 源码为 `a459f1d04dea030833b558be645011ab739c463b`（4.1.26）；
实际 Python 3.11.18、Node v24.22.2 / ABI 137、Native SQLite 3.50.4。
构建实际核对上游 1850 个文件完整性、固定 Python 依赖及关键扩展导入；
[构建标识](evidence/native-temporal-20261113/release-build.json)、
[Python 包版本](evidence/native-temporal-20261113/python-packages.json)、
[SBOM](evidence/native-temporal-20261113/sbom.cdx.json) 已保存。
SBOM 覆盖已安装包和库元数据；打包后的上游依赖另由完整性清单覆盖，不宣称完成漏洞扫描。

未选择新镜像仓库，RepoDigest 暂无；镜像 ID 不能冒充远端 Registry Digest。
可交付的三镜像离线包为最终构建目录下
`distribution/runtime-os-temporal-d1d706f72bdb.tar.gz`，1,602,040,714 字节，SHA-256：
`e842f70d9d516e24c806bff87ea45aee3ca04a8d5929a3b8b062e8e47ea1bb97`。
[包记录](evidence/native-temporal-20261113/image-package.json) 与
[Docker Manifest](evidence/native-temporal-20261113/docker-manifest.json) 可核对三项内容。
Temporal 镜像固定为部署脚本中的官方 Registry Digest，需随部署依赖准备。

清理前归档 inspect/log，确认本专项 Executor 归属、进程已停止且持久化关闭标记存在后，
删除 38 个 Core 测试容器及两个初始 WIP 镜像。实际可用磁盘增加 130,633,728 字节
（约 124.58 MiB）；共享层/构建缓存仍保留，所以不以镜像显示大小计算回收量。
保留最终十个服务容器、`ea727660584c` 回滚镜像、原生产镜像、工作区、停止 fence 和全部数据卷。
未执行全局清理。三个临时测试账户已禁用，测试角色授权已撤销，原管理员未变更。
[清理记录](evidence/native-temporal-20261113/cleanup-report.json)、
[测试授权回收](evidence/native-temporal-20261113/test-access-cleanup.json)、
[生产及候选最终健康检查](evidence/native-temporal-20261113/final-health.json) 均已保存。

两个 Docker 节点部署在同一物理服务器，不代表跨主机高可用。若旧主机及独立停止控制
一起不可达，系统保留待协调状态，不能在未确认停止时启动可能双写的替代执行。
生产 PKI 轮换、跨机可信 fencing 及更多轮次长时间容量测试仍需另外实施。

原始运行证据目录：`/opt/runtime-os/native-temporal/rc-20261112/evidence/`；
最终固定源码构建目录：`/opt/runtime-os/releases/temporal-native-d1d706f72bdb-20261113/`。
28 份真实 Workflow History 已导出并校验哈希，未发现凭据载荷；清单为
[workflow-history-manifest.json](evidence/native-temporal-20261113/workflow-history-manifest.json)。
348 项成功及失败轮次的原始本机验收输出已另行上传服务器受限证据包，
[原始证据包校验记录](evidence/native-temporal-20261113/raw-acceptance-archive.json) 可核对大小和哈希。
本目录 [manifest.json](evidence/native-temporal-20261113/manifest.json) 按 Git 保存的文件字节
计算 SHA-256；Windows 检出若自动转换换行，应使用 Git blob 字节校验。
所有凭据、签名 grant 和私有 Compose 均留在服务器 private 目录，不进入 Git。
