# Native 按任务动态解析平台模型（2026-11-12）

功能源码：`574f722f1dca44b642e8439cf9af6944742c1943`。主环境 18100 已部署，18353 监控仍直接读取主 Java。未修改官方 Cline 4.1.26 Agent Loop、业务授权、共享模型配置或分析决策。

## 当前行为

任务所选逻辑模型从现有 `PlatformModelCatalogService` 的已发布、已启用记录实时解析，不再依赖 Native 的手工模型白名单。默认模型也读取平台当前已发布默认值。用户只传选择的模型名称，不可传地址、密钥或任意内部代理 URL。

Java 签名任务包含逻辑模型、实际 provider 模型和二者绑定的档案 ID，不包含地址或密钥。Node 通过部署固定的 `/internal/runtime/native-coding/model` 回调请求连接配置；Java 校验签名、租户/用户/任务/run、精确持久化绑定、当前授权、期限和取消状态后返回。响应标为 `no-store`。完整 `/chat/completions` 地址按 Native 传输要求归一化为基础地址。

真实密钥仅进入可信 Node 代理的私有任务记录，不进入 Core 的配置、任务签名、工作区、镜像或监控。Core 仅收到本任务代理凭据和实际模型名。执行器独立校验签名和动态模型开关；其他租户、未签名描述符、被篡改的实际模型名或额外密钥字段均不能获取授权。

每个任务使用自己的模型连接快照，避免多个任务相互覆盖模型配置。新增发布模型、切换模型、更新凭据及默认模型对新任务生效，无需更新 Native 模型名单或重启；运行中任务保持已取得的快照，不在 Agent 循环中热换模型。未发布或停用模型不被默默替换为默认模型。发布模型的实际 provider 名若在任务分配期间改变，会明确拒绝原绑定。

当前传输支持平台的 OpenAI 兼容协议（`openai` / `auto`）；不宣称任意供应商私有协议均已适配。平台原有请求和任务授权继续生效；没有新增搜索/模型请求次数上限。

## 配置与兼容

启用配置：Java 私有连接文件 `dynamicModels: true`；Node 每租户配置固定 `runtimeModelAuthority`；Executor 顶层及对应租户配置 `dynamicModels: true`。控制地址仍为已有 18360，模型回调使用主 Java 回环端口 18180。这些是部署授权开关，不是用户任务字段。

旧的静态 `modelProfiles`、模型名称表和旧版本签名继续兼容。未启用动态模式的旧部署仍保持原行为。主部署已启用动态模式，旧静态映射未删改，也不再承担新任务动态模型解析。旧的 `publish-native-model-profile.py` 仅供静态兼容部署使用，日常发布模型不再需要它。

## 验证

Java 回归及新增模型授权接口测试通过，含 1 项既有跳过，详见 [Java 汇总](evidence/native-dynamic-models-20261112/java-summary.txt)。新增测试覆盖启动后模型新增、凭据更新、默认值变更、未发布/禁用拒绝、协议拒绝、签名绑定、取消和响应缓存控制。

新镜像 Linux Node 回归 101/101，通过签名、Executor 租户开关、模型描述符篡改、固定回调、HTTP 拒绝、响应体预算等检查。两个依赖此前专项归档的历史证据测试文件继续排除，没有将其算作通过。

| 主环境真实任务 | 原静态名单 | 结果 |
| --- | --- | --- |
| qwen3.8-max | 不存在 | Python 分析、源码/报告/结果交付、数值及 Silo SHA-256 验证通过 |
| qwen3.8-flash | 不存在 | 无配置同步或服务重启直接切换，同项检查通过 |

两次均通过 18100，以已授权 Agent 身份使用明确标记 SYNTHETIC 的 Parquet。Cline 自主生成并执行 Python，独立结果为 3 行、总计 47.5、均值 47.5/3。实际 Provider 回执分别确认所选模型，没有回退；两个 Worker 隔离。Java 静态映射、Node 静态档案与 Executor 静态模型名称表均与部署前一致，证据见 [动态路由证明](evidence/native-dynamic-models-20261112/dynamic-model-proof.json)。另外检查 Worker 仅使用代理凭据、工作区不含供应商密钥。

本次两个已停止验证容器已保存日志后删除。再次确认隔离 Docker 中当前 Worker 镜像仍存在，并将当前及紧邻上一版 API/Node 镜像注册到 `/opt/runtime-os/releases/image-retention.json`，避免清理器因没有活动 Worker 而删除运行或回滚镜像。

## 镜像与回滚

* API：`runtime-os-api:dynamic-model-20261112-r1`，ID `sha256:4a0aee24a5b9ff1d2ec0fa62e21e31ed67838fffa15e07ef54b15e382255f941`。
* Node/Executor：`runtime-os-coding-node:dynamic-model-20261112-r1`，ID `sha256:8d9238cd519d478fae77b448d514584a2ceec10d073ebf6dc47980c7fab502ce`。
* 源码上传后在服务器构建，保留官方 Runtime 固定版本与原 Python 依赖；不依赖手工改测试容器。镜像未推新仓库，以上为本地 image ID，不虚构 Registry Digest。
* 原始构建、部署及验收证据：`/opt/runtime-os/releases/dynamic-model-20261112-r1/`。
* 回滚：排空活动任务，检查有无后续配置变动，再运行该目录下 `python3 rollback.py`，恢复上一版 API/Node 和本次修改前的私有配置。Silo、MCP、News、数据库、共享模型发布记录未替换或删除。

此次为已授权主环境功能升级，不代表此前正式 1.0.0 的全部场景五轮稳定性门槛或所有供应商模型都已逐个验收。
