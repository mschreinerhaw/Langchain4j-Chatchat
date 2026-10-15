# Node Coding Agent 容器（D1 staging）

此服务只运行 Cline Coding Loop，使用内部 HTTP 调用 Java Runtime OS。生成的 Python 只能经 Runtime 受控工具网关送往已有 Python Sandbox。镜像内不挂载 Docker Socket、业务数据库或用户 uploads；`coding-agent-node` 只注册 `python_execute`、`metadata_read`、`artifact_read` 三种受合同约束的工具。

构建：在仓库根目录执行 `docker build -t runtime-coding-agent:1.0 coding-agent-node`。`compose.yml` 将 Node 端口仅映射到宿主 `127.0.0.1:8791`，容器使用非 root 用户、只读根目录、无新增 Linux capabilities 和内存/CPU/PID 限额。宿主 Java 通过 `http://127.0.0.1:8791` 调用。先安装下面的专用回调网关和网络边界，再设置 `RUNTIME_CODING_CALLBACK_URL=http://172.30.91.1:18081`、`RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS=172.30.91.1`；模型连接由 Java 的服务端配置提供。

在 Linux 宿主创建独立密钥文件，至少 32 个字符。Compose 文件使用 UID `1000`、专用 GID `32101`：宿主密钥文件应为 `root:32101`、模式 `0640`，然后将绝对路径赋给 `CODING_AGENT_SHARED_SECRET_FILE`。不要把密钥放进仓库或写入 Compose 环境变量。启动前确认固定网段 `172.30.91.0/24` 未被占用；Java 的 `chatchat.coding.cline.allowed-peer-addresses` 必须显式包含 `172.30.91.10`，并配置相同共享密钥。只有 D1 全链路验收后才能打开 `chatchat.coding.enabled` 与 `chatchat.coding.cline.enabled`。

`docker compose -f deploy/docker/coding-agent/compose.yml up -d --no-build` 可运行已构建镜像。`GET /healthz` 需要 Bearer 密钥。Node 回调拒绝跳转，Java 回调同时核验对端地址、密钥、时间戳、nonce、合同 hash、租约和 fencing token。固定容器 IP 是为了限制 Java 回调来源；变更网段或扩容时须同步调整来源控制与实例寻址。

**网络边界：** 此 Compose 使用普通 bridge，必须配合宿主网络策略。Linux root 执行 `CODING_JAVA_PORT=8080 bash deploy/docker/coding-agent/install-network-boundary.sh`，安装独立 Nginx 实例 `/opt/runtime-coding-gateway` 和 `runtime-coding-boundary.service`。可用 `NGINX_BIN` 指定既有 Nginx 二进制；隔离联调使用 `CODING_JAVA_PORT=18080`。脚本只管理 `172.30.91.0/24` 的命名防火墙链，不改全局默认策略。专用入口为 `http://172.30.91.1:18081`，仅代理已列举的 Coding POST 回调；Node 到宿主其他端口及转发网络的连接均拒绝。设置 `RUNTIME_CODING_CALLBACK_URL=http://172.30.91.1:18081`、`RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS=172.30.91.1`，Java 允许代理的 loopback 来源。Compose 禁用容器 DNS 与 IPv6。部署前核对该网段仅供 Coding 使用；变更网段/扩容需同步更新策略。

执行 `docker exec -i <容器名> node --input-type=module < deploy/docker/coding-agent/verify-network-boundary.mjs`，检查 Java 认证可达、通用 API 路径拒绝、MCP/数据库/OpenSearch/外网均不可达。只有实测通过才可认定该实例边界生效；宿主 Docker/防火墙升级或重启后须复验。

**回调签名：** Node 与 Java 必须成套升级。签名版本头为 `X-Chatchat-Signature-Version: 2`，HMAC 输入为 `v2\nMETHOD\nPATH\nTIMESTAMP\nNONCE\nSHA256(原始正文)`，正文最多 1 MiB。Java 拒绝旧签名、正文篡改与重放；任务合同版本仍是 `runtime_os.coding.v1`，与签名版本独立。尚未实现动态补给的 v2 合同会明确返回 `VERSION_UNSUPPORTED`，不能当作 v1 执行。

真实模型→Sandbox→六项证据、故障修复和持久事件游标重放已在隔离环境验证，见[本轮部署记录](../../../docs/runtime-os-coding-followup-deployment-20261015.md)。元数据/产物读取回调、动态补给 v2 和历史 provider 超时核对仍有缺口。Node 重启后内存会话消失，Java 必须先核对未知操作，不能盲目重复派发。生产 Coding 保持关闭，不能据此宣称 D1/G3 全部通过。
