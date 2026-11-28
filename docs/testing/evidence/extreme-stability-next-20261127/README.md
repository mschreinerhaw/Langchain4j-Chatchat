# 极限稳定性专项第二阶段执行记录

基线 `313f35664afa1983326ee7e8fec9fb8a71d96673`。业务源码保持冻结。本目录只记录实际运行结果、环境边界和待验证项。

## 磁盘核查

| 项目 | 观测结果 |
| --- | --- |
| `/opt` | 约 180 GiB，总使用率 92%，可用约 15–16 GiB，inode 使用约 7% |
| `/mnt/disk01` | 独立设备 `/dev/sdc`，约 100 GiB，使用约 32%，inode 使用约 1% |
| 根分区 | 使用率约 84–85%，可用约 7.2 GiB，inode 使用约 5%。`/var/lib/containerd`、`/var/tmp` 和 `/var/log` 均落在根分区 |
| 共享 Docker | 数据根 `/opt/docker`。物理扫描约 84.9 GiB，其中 `overlay2` 约 73.5 GiB、卷约 11.0 GiB。容器日志文件约 123 MiB |
| 共享 BuildKit | 默认构建缓存逻辑统计 1.789 GB。另两个共用 builder 分别报告 1.397 GB、9.848 GB 可回收。逻辑统计和物理大小可能重叠 |
| 其他大目录 | MySQL 安装及数据目录约 54.4 GiB，现场运行中的 `mysqld` 工作目录在其 `data` 下。Runtime OS 目录约 5.6 GiB。既有 API 和 MCP 服务日志目录分别约 70 MiB、15 MiB |
| 已删除仍被持有的文件 | `lsof +L1` 去重共约 146 KiB，未发现记录落在 `/opt` 主设备 `8:16`，没有可释放的大文件 |
| 僵尸进程 | 快照 7 个。其中 1 个属于既有 Temporal 容器，6 个属于既有 PDF Worker，父进程分别为 temporal-server 与 node。样本仅用于存在性和归属判断 |
| 本轮构建写入位置 | 通过独立 Docker socket 执行 `docker info`，数据根位于 `/mnt/disk01`。containerd 数据、执行目录、临时目录、Maven、npm 缓存和源码构建目录均解析到 `/dev/sdc` |
| 定向清理 | 逐一比对 API、MCP 镜像构建上下文内的 JAR 副本与保留的原始产物。删除这两份本轮副本释放约 654 MiB。共享卷、镜像、日志和回滚产物未纳入清理。[清理记录](server-scoped-cleanup.txt) |

采样从约 22:59 至 23:00，`/opt` 使用量在约 172454020–172554312 KiB 之间波动，短窗采样不能推断长时增长率。达到 80% 还需释放约 21 GiB。当前可安全确认的清理对象不在该挂载，暂停会让共享分区新增高额写入的业务压测。

Native 依赖传输发现 `docker save | docker load` 的额外写入。接收 daemon 在 `/mnt/disk01`，共享源 daemon 仍在 `/opt/docker/tmp/docker-export-286318971` 暂存导出数据。23:11:45 观测到暂存约 2.9 GiB，`/opt` 升至 94%。23:12:21 导出完成，Docker 自动释放临时目录，`/opt` 回到 92%，可用约 15.4 GiB。已核对两个独立 daemon 的写入边界，后续暂停从共享 daemon 发起大镜像导出。

## 构建与组件状态

| 组件 | 本轮观察 |
| --- | --- |
| Gateway | `chatchat-gateway-baseline:313f35664`，镜像 ID `sha256:020a2a637e804ef81054d07044170e91af30a2228031cecd1cba7ab9c6983efd`，上一阶段已验证隔离 HTTP 及真实短请求 |
| API | `chatchat-api-baseline:313f35664`，镜像 ID `sha256:33fc423364ebe0f2523c69fef92a179817db80711fd79553a3c8bb1dc41c8d09`。指定代理下构建成功 |
| MCP | `chatchat-mcp-baseline:313f35664`，镜像 ID `sha256:4469ad11a2c146477668733f3fd56f6377511e5125ec964fd90fbc867d8e1fa4`。指定代理下构建成功 |
| Cline / Python | 共用 Cline Node 与 Executor 标注修订 `e52c073ea69455eb9b5a09e0e1e1c8e7bab70fe4`，与当前提交存在治理服务和模型回执实现差异。当前提交的新镜像 `chatchat-native-baseline:313f35664` 已构建，镜像 ID `sha256:b50c3dbbf0bc578228af7f0f6b8b280dd041ab01847fe2c0c69713769645a231`。验证 Cline 4.1.27 上游提交 `8075d2e35cf5cad67c24f180c80a05d311b74a3e` 的 1,849 个入口文件，镜像内 Python 3.11.18，构建包含原生模块、Python 运行时和字体校验。[Native 构建记录](server-native-image-build.txt) |

镜像标识为本地镜像 Config ID，`RepoDigests` 为空。Ubuntu 索引和字体包经指定代理分别返回 HTTP 200，观测到的 TTFB 约 0.82、0.69 秒。重试拉取了 68.9 MB 软件包，耗时约 3 分 47 秒；构建安装了 `fonts-noto-cjk` 并通过 `sqlglot` 版本校验。首建主要耗在大包传输和当时的构建时限。[API 与 MCP 镜像构建记录](server-java-images.txt)

独立 Docker daemon 上的断网 H2 冒烟中，API 与 MCP 分别在正常 HTTP 入口返回预期的未认证 401。烟测只读挂载现有内部凭证材料，并为断网模式关闭聊天正文的外部检索存储。此项验证仅覆盖启动和认证入口，未连接真实授权数据和 OpenSearch。[HTTP 启动记录](server-java-http-smoke.txt)

新 Native 镜像中的隔离 Linux 测试最终为 158 项通过、1 项跳过。首轮在默认测试并发与 128 PID 限制组合下出现测试进程 EAGAIN。将测试并发固定为 2 后，另有依赖仓库历史证据文件的测试无法在仅含运行时的镜像中取到 fixture。对同一镜像只读挂载该提交的测试证据并复跑后通过。保留的跳过项需要上游源码 checkout。两次调整仅限测试运行条件。[Linux 测试记录](server-native-linux-tests.txt)

## 60 秒边界

按 59、60、61、90、120、180 秒注入首有效输出延迟。普通模式和 Runtime 管理模式全部返回 200，首输出时长与注入时长一致。诊断模式在明确配置 60 秒首包预算时，59 秒成功，其余五档约在 60.01 秒返回 `first_packet_timeout`。独立心跳样本连续发送 SSE 注释，无首个有效输出，直到调用方取消才返回，未产生 `[DONE]`。此处验证的是网关转发函数及本地 Mock，原现场任务的 60 秒责任层仍需链路回放。[原始结果](gateway-field-timing.json)

现运行 Nginx 的 `nginx -T` 显示 `proxy_read_timeout`、`proxy_send_timeout` 均为 1800 秒，且禁用代理缓冲。源码巡查发现 Cline 通用 RPC 使用 60 秒 deadline，Cline 治理的 Skills 请求也使用 60 秒信号。Gateway 普通请求与 Runtime 管理请求当前不使用诊断首包时限。这些是不同路径，未将任何一处静态匹配作为原现场失败的根因。前端、Java HTTP、Runtime 调度、参数绑定与 MCP 的跨层定时仍待完整隔离验证。

在线 Gateway 的 SQLite 快照配置修订范围为 1–13，未找到 `first_packet_timeout` 记录。现场截图出现过修订 33/34。当前在线环境取证无法直接关联现场请求与其历史配置。

## 组合故障与真实业务

7 个组合模拟覆盖小窗口和多段数据叠加、慢响应期间取消、工具参数未发完即断线、逐次放大请求数据。工具断线为 502 且没有 `[DONE]`，取消和失败后网关在途数均归零。窗口按字节模拟，未测真实 Token 边界或 Cline JSONL 的持续增长。原有 44 项按场景、状态、错误、结束标记和在途数比对复跑一致。[原始结果](gateway-combined.json)

独立 HTTP 测试启动未修改的 Gateway 并设 2,048 字节请求上限。含中文内容的 2,047、2,048 字节请求到达上游，2,049 字节、同一超限请求重发及 4,096 字节请求均在网关返回 413，未到达上游。最后缩小到 2,047 字节后请求成功。此结果用于区分 HTTP 请求体与模型 Token 限制，未验证 Runtime 对 413 的自动重试和 JSONL 增量。[真实 HTTP 边界结果](gateway-request-limit.json)

对现有 Cline `Journal` 进行独立写入和重开实验。每次追加同一 16 KiB 测试正文的重试标识，64、192、448 条事件时，文件分别约为 1.06、3.17、7.39 MB。重开日志的单次堆内存增量约为 2.17、6.53、15.25 MB。实验验证该存储组件的追加与整文件重放成本，对 Runtime 实际重试频率、会话压缩和长期运行的结论仍待联机采样。[日志增长结果](cline-journal-growth.json)

场景 5.1–11 均待通过完整隔离环境的正常入口执行。未将历史产物、单个模型短探针或断网 H2 检查作为真实业务验收。InnoDB 模板链路、Harness 真实取消与恢复、多租户联合限流、服务重启和长时资源趋势仍缺少本基线的运行证据。
