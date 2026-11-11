# Native Coding Node 单镜像改造记录（进行中）

后续通用 S3 HTTP 服务、最新镜像与新队列证据见 [2026-11-11 实施记录](native-s3-gateway-20261111.md)。
本文保留此前客户队列及失败历史，其中的镜像 ID 不是最新部署镜像；正式应用 HTTP/SPI 与完整业务验收仍不能用综合合成数据测试代替。

更新日期：2026-11-11。**尚未达到最终交付条件；未提交或推送本次改造，未执行最终资源清理。**

当前分支：`feature/runtime-cline-cli-native-20261107`，基线提交 `e06eabaf4c8554469ab48fc8841ef1ccc200986e`。
生产默认 Coding Agent 未切换。新增 Silo 工作空间服务默认关闭，复用现有授权数据、用户资产登记、SQL 检查点和 Silo。
Java → Silo → Native → Silo → Java 已在独立服务夹具中实际运行；**应用 HTTP、正式 Coding SPI、现有 Runtime 任务授权入口与新执行链的接入尚未完成**，不能用夹具验收代替应用验收。

当前 Native 镜像：`runtime-os-coding-node:cline-4.1.26-python3.11`，本地镜像配置 ID：
`sha256:7ee4b2b77a89220c023fbb8e67d6861a0bea8d8fab3c15b0f9e8f9b35cf7d986`。
CLI 对照镜像配置 ID：`sha256:fd52986547d9470c84891fd4713d8b4479c97590c8cb0df7203238eedb1ace4a`。
`RepoDigests` 为空，以上不能冒充仓库 Manifest Digest。官方 v4.1.26 源码提交为
`a459f1d04dea030833b558be645011ab739c463b`；既有 Standalone 包含 Host 适配，不能称为未经修改的官方二进制。
本次未修改官方 Core 或 Agent Loop。

## 固定客户账户队列的真实结果

第一轮预登记 Native 8–12 与 CLI 4–8，共 10 个独立 Session，全部执行结束，无样本剔除。
固定相同模型 `qwen3.7-plus`、low 推理、provider-default 输出参数、原始四文件哈希、任务提示词、1200 秒期限和对应镜像。
共 577 次实际模型请求、31,458,205 Token；费用数据未提供，不能记成零。
原始 Java v3 的 17 项数值和较窄的来源检查全部通过，但完整报告验算只有 **Native 3/5、CLI 1/5** 通过。
历史 `BUSINESS_COMPLETED` 等夹具状态原样保留；下面的完整业务结论优先，不能把旧状态当作最终交付成功。

| 独立任务 | 模型请求 | Token | 耗时（秒） | 完整业务验算 |
|---|---:|---:|---:|---|
| Native 8 | 53 | 2454021 | 565.810 | 通过 |
| Native 9 | 87 | 5065940 | 1010.548 | 失败：把仅卖出的证券写成日内双向买卖，并声称未声明的收盘后快照 |
| Native 10 | 33 | 1267136 | 564.144 | 通过 |
| Native 11 | 63 | 3997362 | 872.124 | 通过；报告成本价按两位小数显示，已按原始值舍入独立验证 |
| Native 12 | 36 | 1901934 | 636.522 | 失败：把业务日期自行解释为日终快照 |
| CLI 4 | 54 | 2809851 | 669.955 | 失败：把累计实现盈亏缩窄为历史已平仓盈亏 |
| CLI 5 | 102 | 6852601 | 1159.233 | 通过 |
| CLI 6 | 47 | 2235261 | 509.286 | 失败：把当日盈亏缩窄为仅持仓浮动部分 |
| CLI 7 | 43 | 2127210 | 590.572 | 失败：历史已平仓口径和收盘后快照均无来源声明 |
| CLI 8 | 59 | 2746889 | 502.352 | 失败：无来源支持却断言盈亏包含资金利息等组成 |

通过样本均由 Java 从 Silo 重读不可变输入与登记产物，并在断网新容器中只使用登记的源码和输入重跑实际原生命令。
起始没有旧报告或结果，完整 JSON 与报告逐字节一致。另用独立 PyArrow 程序核对报告明细和主要业务数值；源码 AST 检查发现真实 Parquet 读取与聚合，没有主要业务答案的硬编码数值。
这些验证仍是独立服务夹具证据，不代表正式 Runtime HTTP / SPI 验收。

观测更正：Native 8 早期只统计 `tool.completed`，曾误报 15 次 Python 命令且没有异常。
完整统计 `tool.completed` 和 `tool.failed` 后，实际为 16 次命令回执、15 次成功、1 次 ModuleNotFoundError，后续恢复并成功交付。
原始失败事件一直保留，已新增 `execution-observation-correction.json`；不得再引用早期“没有 Python 异常”的结论。
命令回执不是操作系统进程总数，自动修复次数缺少直接事件时保持未知，不构造精确计数。

原始 Parquet 59 行、18799 字节，物理列 `p0/c0/p1/c1`，SHA-256：
`1405e00c94adde35c29fef8f22e8d0ecbc79349804a3f4bb61f4b39b977b128a`。
原始文件、列名、类型、业务口径和判定标准均未为通过测试而修改。
所有 10 个任务已补充完整审查证据包，经 Java 现有资产服务上传 Silo，再 GET 读回校验 SHA-256。
证据根目录：`/srv/runtime-os-coding-node-20261110/`；逐任务结论见 `runs/<case>/overall-acceptance.json`，
原始记录见 `events.json` 或官方 ACP NDJSON、`model/`、`versions/`、`delivery-versions/`。
补充归档回执见 `raw-evidence-silo-archive-review-v1.private.json`，对象位置只保存在授权测试主机，客户数据和原始模型日志不进入 Git。
完整队列审查见 `customer-fixed-cohort-20261111/full-business-review.private.json`。

## 最小校验反馈整改与第二轮中断

`NativeCustomerDeliveryVerifier`、`CustomerReportSourceMeaning`、`CustomerIntradayClaimVerifier` 和测试反馈适配器增加实际结果值/类型、逐字段错误、日内双向成交、来源未声明的盈亏组成/期间及日终快照检查。
只向当前原生会话反馈真实错误，不注入验算答案、分析代码或固定文件名。
v6 Java 归档 SHA-256 为 `252d59853c13b0e37d9733edeb3fb9a36342a3343b408f8527d00be1c8beb81c`。
19 项相关 Java 测试、3 项反馈测试通过；从原 Silo 不可变版本重新校验上述 10 个样本，预期的 4 个通过和 6 个失败全部匹配。
此次回归零模型调用，没有改写旧结果或旧产物。

第二轮预登记 Native 13–17、CLI 9–13，使用新校验反馈，其他模型、镜像、输入和提示词保持相同。
**该队列因下面的环境故障已停止派发，不能声称完成五次对照。** Native 13 实际 34 次请求、1604643 Token、491.246 秒，API read ETIMEDOUT，失败记录保留。
CLI 9 实际 39 次请求、1714597 Token、842.610 秒，原校验结束，完整报告复核仍待完成。
Native 14 准备时 PostgreSQL 连接超时，未启动模型；其余计划任务没有派发。合计 2 次真实执行、8 次未执行，不能用新增样本替换失败样本。
记录见 `customer-fixed-cohort-source-v6-20261111/` 和 `evidence/customer-fixed-cohort-source-v6-environment-interruption.json`。

## 资源隔离试验及环境故障

宿主机是 cgroup v1；原 rootless Executor 无法落实每 Worker 内存、CPU 和 PID 上限，不能开启 prototype 绕过后宣称通过。
新增可选的专用 rootful daemon 策略保持原默认 rootless 策略，要求固定 daemon ID、私有 socket/data-root、真实 cgroup 支持、固定进程启动标识，以及 daemon 和 containerd 各自独立的网络命名空间。
统一 Python 镜像按 UID 10001 运行，任务 input 单独只读挂载；调用方仍不能选择镜像、宿主机路径、网络或特权参数。
这些新增 Executor 代码尚未进入当前固定镜像，也未接入生产默认节点。

**一次实际环境事故必须保留：**前两次专用 daemon 初始化使用 `bridge=none`，仍移除了宿主机现有 `docker0`，影响 PostgreSQL 和依赖默认网桥的端口连通。
没有停止共享容器。已停止自身空测试 daemon 与后续模型任务派发，按现有容器邻居缓存中的原网关 MAC、Docker 原网络信息和原 veth 对应关系重建网桥并接回现有网卡。
PostgreSQL 握手恢复，共享容器启动时间未变化。原始日志、失败配置及恢复命令保留于 `evidence/shared-docker0-repair.json` 与 `strict-daemon-1/2`。
这不是无影响的试验，不能仅凭容器仍显示 Up 就声称服务从未受影响。
第三次将 dockerd 和独立 containerd 在执行前分别放入新网络命名空间，固定镜像导入成功，前后宿主机 `docker0` 的 ifindex、MAC 和地址一致。
`strict-daemon-3.json` 记录各自 PID、启动标识、网络命名空间 inode、镜像和数据目录；保留前两次失败，不重启或删除失败记录。

首个零模型资源探针发现严格 umask 下生成的 Worker 配置文件组读权限被屏蔽，实际 EACCES 启动失败；已改为显式 chmod，并以新探针验证，旧探针及容器保留。
资源探针属于基础设施验证，不计作真实模型业务成功、H 场景五次取消恢复或正式 Runtime 验收。

## 其他实测与剩余交付条件

真实 Silo 大文件组件：16777247 字节，分三段 8388608 / 8388608 / 31 字节；
SHA-256 `94e0258556f2b5e81f0ab9f51f7465ae78c2f7318e26da6a73f6b61835b8f705`。
Java 验证真实分段版本、合并对象、全文件 SHA 和全部字节模式。
单次截断后安全重试、持续截断不暴露文件、错误 SHA 拒绝、一次 503 后重试、真实 Silo 签名错误 403 拒绝和预取消均通过。
截断响应及预转发 503 明确标记为人工故障注入；不是 Silo 自发故障，也不代表 Native 进程取消恢复已通过。

旧先导任务 Native 1–7、CLI 1–3 的全部成功与失败记录仍保留，不能拼成固定版本五次通过。
Native 4/5 的 H2 2.3.232 文件控制库曾出现新 JVM 读回 revision 13 后再打开变为 5 的实际回退。
已冻结失败库和取证副本，后续夹具改用现有 PostgreSQL 独立验收库和逐任务 schema；20 次写入加 20 次新 JVM 读回通过。
状态回退是观测事实，具体 MVStore 关闭压缩缺陷仍是推断：[H2 #4123](https://github.com/h2database/h2database/issues/4123)、[H2 #4247](https://github.com/h2database/h2database/issues/4247)。

现有边界测试确认只读输入写入拒绝、外任务路径不可见，Silo 正向 GET 成功；篡改租户、任务、有效对象版本或方法均 403。
初次错误版本格式返回 400 的失败探针也保留。
官方 CLI 3.0.74 是 v4.1.26 源码 `apps/cli/package.json` 声明的组件版本；独立二进制 SHA-256
`80ee64f82f895ccc695b95acd18252e00f17186a308257d7b169d444a2b27ef4`，不能据此额外声称已独立证明二进制的精确源码提交。
ACP 经官方 `session/set_config_option` 固定模型，同会话多轮真实执行；没有把 CLI 回执伪装成 Native 回执。

仍待完成：正式 Runtime HTTP / SPI 授权供数与原生 Shell/Silo 交付接入、严格 Executor 下真实模型客户闭环、固定版本 A–H 各五次、真实取消及 Session 恢复、Skills/MCP、监控最终业务验收状态、完整应用构建和部署回滚演练、OCI/仓库 Digest。
普通 Python 错误在直接 Native 会话中已有自主修复证据，不能据此断言旧 Java 默认链已解除所有终止路径。
**关键验收未通过，不执行最终提交推送和资源删除。**之后必须依次核对有效修改与敏感文件、测试构建、非强制提交推送、远端 HEAD、Silo 归档，再按精确资源 ID 和实时归属清理并核算实际磁盘差额。
正式镜像、回滚镜像、Silo 数据、Runtime 数据和共享服务均须保留；不得用全局 Docker prune。

## 源码与执行链对照

| 层次 | 原业务执行链 | 新节点路径 | 当前证据／边界 |
|---|---|---|---|
| Java 授权 | 既有业务授权、模型资源授权与任务合同 | 保留；新增 Silo 工作区服务默认关闭 | `chatchat.runtime.coding.unified.enabled=true` 才装配新服务；应用 HTTP／SPI 对接尚待完成 |
| Native Core | 官方 Standalone，业务 Host 强制远端 Python 与提交回调 | 同一固定 Core，使用原生 `run_commands`，关闭 `requireSubmitAndExit` | 没有修改 Agent Loop、工具执行器或共享模型配置 |
| Python | Native → MCP → Java → Python 控制面 → Sandbox → 产物回读 → Native | Native Shell → 同容器 Python → 本地任务输出 | Parquet 镜像试验已实际执行；旧 Sandbox 留给其他业务 |
| 工程管理 | 远端 Python 文件引用、结果回调、旧代码版本适配 | Cline 自主创建目录和多文件源码；按声明产物验收 | 无固定 `analysis.py` 验收条件；新路径不实例化旧 `CodeHistory`／SDK Worker |
| 输入 | 逻辑路径与 Sandbox 路径跨环境适配 | Java 授权快照 → 版本化 Silo 对象 → Node 传输阶段 → 只读本地输入挂载 | 能力 URL 不写入 Cline 输入清单，也不把 Silo 密钥挂入 Core |
| 输出 | Python 回执、预设 JSONL 与提交回调 | Native 封存 → `delivery_manifest.json` → Silo 分块传输 → Java 独立验证 | Native 完成、退出码零、上传成功均不等于业务成功 |
| 状态 | 原任务／工具／Python 多处记录 | 复用 Runtime SQL evidence checkpoint、用户文件注册表和 Silo | 尚未完成应用任务状态与监控端的最终接入 |

官方源码固定为 `v4.1.26`，提交
`a459f1d04dea030833b558be645011ab739c463b`。使用已有经过 Host 适配的 Standalone
发行目录，不能称为未经修改的官方二进制。已有 Host 适配包括 IPC 事件／权限、
无界面 HostBridge、可选 MCP 上下文和可选提交工具；本次不修改官方 Agent Loop。

远端源码归档没有 `.git` 元数据，按 `upstream-pin.json` 校验核心文件：

| 官方源码文件 | 实测 SHA-256，与固定清单一致 |
|---|---|
| `sdk/packages/agents/src/agent-runtime.ts` | `6bfbebdc58d1b8fffd6778fad847bf19316e0363b820fadf10aa692a1a1a57a3` |
| `sdk/packages/core/src/ClineCore.ts` | `4c4fe321fe6e75e84b475d26e12483a83eb02ec2c145c8326d56dd8e91458ad4` |
| `sdk/packages/core/src/runtime/orchestration/runtime-builder.ts` | `c072fcd16b8452b7a2326c70f0e7a0e6af4a30a2aa75f3e47ff75e34f3c64add` |

原生会话工厂 `apps/vscode/src/sdk/cline-session-factory.ts:1206` 设置
`maxIterations: undefined`；原生 Agent Runtime 对未设置的迭代上限按 unlimited
处理。原生连续错误／异常循环保护保留，不将其误报成 Java 请求次数限制已被解除。
新 Native 本地测试不经过旧业务网关中的 Sandbox 指令与修复门禁。

## 镜像与依赖

Dockerfile：`cline-agent-server/docker/Dockerfile.python`。
构建工具：`cline-agent-server/scripts/build-python-node.py`。
目标标签：`runtime-os-coding-node:cline-4.1.26-python3.11`。

多阶段构建从固定本地镜像取得 Node 可执行文件，在已有 Python Runtime 上运行。
不覆盖 Python 镜像的 glibc／动态加载器，不复制旧 Coding SDK Worker。镜像入口
只启动现有 HTTP/SSE Server，由它为会话启动一个 Standalone Core。输入／上传阶段
可用相同镜像执行传输工具，这些阶段不启动 Core 或其他 Agent Loop。

| 项目 | 已验证版本／固定值 |
|---|---|
| Python 基础镜像 ID | `sha256:3434f7630ff7c54a053c7385710e089d249e65d668699ab96f9603b6e1b27eef` |
| Node 来源镜像 ID | `sha256:ee3100eabd0862d0f60f0777d9884b5d06e50cfeb80e1fb166a22db70ffd793d` |
| Python / Node | 3.11.18 / 24.21.0，Node ABI 137 |
| pandas / pyarrow / duckdb | 2.3.3 / 26.0.0 / 2.0.0 |
| matplotlib / openpyxl / pytest | 3.10.0 / 3.1.5 / 9.2.0 |
| SQLite | Node 原生模块 3.50.4；Python 3.46.1，分别实际执行／导入 |
| Runtime 资产清单 SHA-256 | `40e55f9d35788dd1a208a1229d2a0ccc03432b0f00d59c562b841a19b91d7989` |
| Core bundle SHA-256 | `03ec8a39a97a41a5dd8709d4281e95ad17d89c59429ab80ee51fb3e20da32241` |

保留原直接依赖意图为 `deploy/docker/python-runtime/requirements.in`。
`requirements.lock` 改为远端既有 Python 镜像全部已安装包的精确版本，包含传递依赖，
镜像构建逐项核对并运行 `pip check`。没有删减数据库驱动、科学计算包或字体。
当前是固定版本与固定基础镜像复用；尚不是带完整 wheel 哈希和系统包仓库快照的
从零构建供应链。基础镜像仅有本地 image ID，不能把它冒充 registry manifest digest。

构建过程已实际验证 pandas、numpy、pyarrow、duckdb、matplotlib、openpyxl、pytest、
psycopg、pyodbc、oracledb、scipy、sklearn；Native 模块 `ldd` 和 SQLite 查询通过；
中文字体覆盖及 PNG／SVG 渲染通过。

## 已发现问题与真实试验

| 试验 | 结果 | 处理 |
|---|---|---|
| 首次构建准备 | BuildKit 将裸 `sha256:` 当成仓库名，尝试外网拉取而超时 | 校验本地 image ID 后建立内容标识的本地构建标签；不改变基础内容 |
| 单镜像 Parquet pilot 1 | 12 次真实请求，Native turn completed；无成功 Python 执行，无报告；验收失败 | 基础 Python 用户为 nologin，所有 Shell 返回 `This account is currently not available.`；保留失败文件和事件 |
| Parquet pilot 2 | 12 次请求，实际读取 238073 行／19 列，Cline 编写并执行 Python，生成报告 | 镜像将 runner 的 Shell 设为 Bash；业务数值独立审核尚待汇总，不提前记为通过 |
| 客户 Silo pilot 1 | Java 已将原始快照存入现有 Silo；Node 下载 4 个输入并校验版本／大小／哈希；Native 自主执行中 | 原始客户数据、真实模型记录、源码版本只保留在私有证据目录 |

首轮失败镜像 ID：
`sha256:8c1c2d77ab4a19a67351facb888fdc640e218d553427693f76281d0d690714d9`。
Bash 修复后 Parquet 镜像 ID：
`sha256:7b5398329edbe606369b63f2bce75303c49e0421fc4b57408e4463f2382a774a`。
正在进行客户闭环的镜像 ID：
`sha256:5f899371489d86407a8e5db5353cb05eca5eefe93bb6094853a58b9200806773`。
这些是本地 image ID；当前 `RepoDigests=[]`，尚未发布 registry digest。

本地 Java 已运行 14 项选定测试全部通过；Native 全套为 75 项，74 通过、1 项因
Windows 符号链接权限跳过。Linux 镜像已单独运行符号链接拒绝及传输测试。单元／
组件测试不计入真实业务场景五次验收。

## Silo 交付与栅栏

复用 `AssetBlobStore`、`SiloAssetBlobStore`、`UserAssetService` 和
`JdbcRuntimeEvidenceStore`，不新建文件存储。精确对象版本 GET、精确对象 key PUT
能力有效期不超过 900 秒。大文件按 8 MiB 分块上传，校验每个不可变 part 版本后
用 Silo compose 合并并再次流式复算整体 SHA-256。传输失败最多重试三次，这不是
模型或代码修复次数限制。

对象仍位于原有私有桶的
`tenants/<tenant-hash>/users/<owner-hash>/tasks/coding-node/<task-run-hash>/<epoch>/<delivery-hash>/...`。
上传许可不等于正式发布许可。Java 在最终 SQL 事务中再次检查任务 epoch、状态、
交付哈希后登记。取消在该事务之前取得栅栏，则迟到传输不能登记正式产物。已签发
URL 在过期前可能仍可写暂存 part；不声称签名 URL 能即时撤销。

Node 封存会话后才能准备 manifest；传输只读挂载输出，交付另需容器实际停止证明。
Java 重新读取 Silo 输入／输出；客户独立验算使用 Java Parquet codec 和原有
`customer-account-v1` 指标定义，不运行生成的 Python。完整清单和业务验证结果
也存入 Silo。源码文件名不参与业务成败判定。

## 当前剩余工作与隔离边界

- 应用 HTTP／Coding SPI 入口、现有平台任务状态、监控页面的完整接入。
- 客户任务报告、指标、源码执行及 Silo 交付独立验收；完成后固定版本五次重测。
- A–H 各五次、官方 CLI 同等功能对照、并发、真实取消／恢复、MCP 和 Skills 验证。
- Silo 实际拒绝跨作用域访问、失败传输及取消竞态的在线证据。
- 最终镜像 digest／源码版本、部署与回滚、证据归档、Git 提交推送和最终清理。

当前使用隔离的 Java 服务测试驱动和独立 SQL 注册表，不代替应用 HTTP 授权验收。
现有 rootless Executor 在该宿主环境有 cgroup 支持限制，不得宣称已满足生产资源
隔离；镜像合并也不是完整执行隔离机制。不得因单镜像构建成功切换生产默认 Agent。

## 证据和最终清理条件

远端私有目录：`/srv/runtime-os-coding-node-20261110/`。其中 `evidence/build-*`
保存构建日志和文件指纹；`runs/` 保存全部试验、模型回执、原生事件和源码版本；
`customer-fixture-1/` 保存 Java/Silo 协议与独立验证记录；`private/` 不是交付源码。
早期 CLI 3.0.74 先导证据仍在 `/srv/cline-python-runtime-20261110/`，不能算作
Standalone v4.1.26 新节点验收。

仅在真实业务验收通过、Silo 归档验证、无其他任务占用、Git 正常提交推送且远端
HEAD 与本地一致后执行最终清理。按资源登记的精确容器／镜像 ID 操作，保留正式
镜像、回滚镜像、全部失败证据和共享持久数据。不执行全局 prune。实际回收空间
按清理前后文件系统可用字节测量，不累加镜像显示大小。
