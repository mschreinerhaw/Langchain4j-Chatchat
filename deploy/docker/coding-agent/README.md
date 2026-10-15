# Node Coding Agent 容器（D1 staging）

此服务只运行 Cline Coding Loop，使用内部 HTTP 调用 Java Runtime OS。生成的 Python 只能经 Runtime 受控工具网关送往已有 Python Sandbox。镜像内不挂载 Docker Socket、业务数据库或用户 uploads；`coding-agent-node` 只注册 `python_execute`、`metadata_read`、`artifact_read` 三种受合同约束的工具。

构建：在仓库根目录执行 `docker build -t runtime-coding-agent:1.0 coding-agent-node`。`compose.yml` 将 Node 端口仅映射到宿主 `127.0.0.1:8791`，容器使用非 root 用户、只读根目录、无新增 Linux capabilities 和内存/CPU/PID 限额。宿主 Java 通过 `http://127.0.0.1:8791` 调用；Node 通过 `host.docker.internal:8080` 回调 Java，模型连接仍由 Java 的服务端配置提供。

在 Linux 宿主创建独立密钥文件，至少 32 个字符。Compose 文件使用 UID `1000`、专用 GID `32101`：宿主密钥文件应为 `root:32101`、模式 `0640`，然后将绝对路径赋给 `CODING_AGENT_SHARED_SECRET_FILE`。不要把密钥放进仓库或写入 Compose 环境变量。启动前确认固定网段 `172.30.91.0/24` 未被占用；Java 的 `chatchat.coding.cline.allowed-peer-addresses` 必须显式包含 `172.30.91.10`，并配置相同共享密钥。只有 D1 全链路验收后才能打开 `chatchat.coding.enabled` 与 `chatchat.coding.cline.enabled`。

`docker compose -f deploy/docker/coding-agent/compose.yml up -d --no-build` 可运行已构建镜像。`GET /healthz` 需要 Bearer 密钥。Node 回调拒绝跳转，Java 回调同时核验对端地址、密钥、时间戳、nonce、合同 hash、租约和 fencing token。固定容器 IP 是为了限制 Java 回调来源；变更网段或扩容时须同步调整来源控制与实例寻址。

**网络边界：** 实测 Docker `internal: true` 会阻断容器访问宿主 Java API，并阻断宿主端口映射，因此此 Compose 使用普通 bridge。普通 bridge 本身不强制出站白名单；生产部署还需在宿主防火墙或编排平台网络策略中仅允许 Node 到 Java 受控网关，并验证 DNS、重启和扩容行为。未完成该限制时不能声称容器网络已达到生产隔离要求。

当前 Java 仅实现部分受保护模型回调；Python 执行、元数据/产物读取、独立验证和最终证据提交回调尚未闭合。Node 进程重启后内存会话消失，Java 必须先核对未知操作，不能盲目重复派发。此 Compose 可用于隔离联调，不能据此启用生产 Coding 任务或宣称 D1/G3 通过。
