# HTTPS 部署与客户端接入

## 初始部署

`llm-compatibility-gateway:1.1.1` 由 Node.js 在容器内提供 TLS 1.2 和 TLS 1.3。推理、管理分别监听 8443、18081，使用同一张含全部服务域名或 IP SAN 的证书。HTTPS 监听端口由 `HTTPS_PORT` 设置，宿主映射由 `HTTPS_PUBLIC_PORT` 设置。对外使用 443 时设置 `HTTPS_PUBLIC_PORT=443` 和所需监听地址，容器仍以非 root 用户监听 8443。管理端口默认只对本机发布。

### 无 CA 的内网

将 `TLS_SELF_SIGNED=true` 写入 `.env`，把客户端实际使用的域名或 IP 填入 `TLS_SERVER_NAMES`，启动证书目录保持为空。首次启动网关在容器内离线生成一张 365 天有效的自签服务器证书，私钥加密保存在 `gateway-certs` 卷。也可在启动目录放入已有的自签 `server.crt` 与 `server.key`。这两种方式均使用证书 SAN 和有效期校验。

在 Docker 宿主机导出公开证书，核对指纹后分发给客户端。首次登录管理台也需要浏览器信任该证书。

```sh
docker compose exec -T llm-gateway node dist/server/export-trust.js > gateway-server.crt
```

Windows PowerShell 使用 `docker compose exec -T llm-gateway node dist/server/export-trust.js | Out-File -Encoding ascii gateway-server.crt`。Cline/Node 可使用 `gateway-server.crt` 作为补充信任，LangChain4j 使用页面提供的同一证书导入 Java PKCS12 TrustStore，curl 传入 `--cacert gateway-server.crt`。网关面向自签上游时可在对应服务的证书信任字段填入上游的自签公开证书。

证书管理可上传自签证书或生成并启用新的自签证书。自签叶证书轮换后，客户端须信任新证书。保存实际使用的信任文件与 HTTPS Base URL，应用随后只提交网关 Key 和选中的对外模型 ID。

### 已有 CA 签发证书

1. 使用企业 CA 为推理地址和管理地址签发服务器证书。证书至少含一个 DNS SAN 或 IP SAN。将叶证书和各中间证书合并为 PEM 格式的 `server.crt`，将对应的私钥保存为 `server.key`。企业或私有 CA 根证书放在可选的 `ca.crt`。
2. 将目录设为 `TLS_BOOTSTRAP_HOST_DIR`，默认 `./tls-bootstrap`。容器 UID 1000 需要读取权。在 Linux 可通过所属组或 ACL 授权，限制私钥仅供管理员和容器用户读取。配置 `TLS_SERVER_NAMES`，以英文逗号分隔，首项用作接入示例的主名称。存在私钥的目录保存在源码树之外，或使用默认忽略的启动目录。
3. 按 README 配置 `.env`。启动时校验域名、私钥、证书链和证书有效期。首次通过验证后，生效的私钥和待签名 CSR 私钥以 AES-256-GCM 存储在独立的 `gateway-certs` 卷。日志和数据库各自使用独立卷。
4. 面向内网客户端配置相应域名解析和 CA 信任。上游使用 HTTPS 时，在「上游模型服务」中可以填入该服务的 PEM CA 链。上游证书验证未禁用，证书 SAN 需要匹配上游地址。
5. 明文测试入口只在显式加入 `docker-compose.http-test.yml` 时发布到宿主回环地址，默认端口 18080。HTTPS → HTTP、HTTPS → HTTPS、HTTP → HTTP 和 HTTP → HTTPS 使用相同的模型映射及规则。

```sh
docker compose up --build -d --wait
# 有明确测试需求时启用回环 HTTP 入口
docker compose -f docker-compose.yml -f docker-compose.http-test.yml up --no-build -d --wait
```

## 证书轮换和信任

管理台「HTTPS 证书」支持上传 PEM/CRT + KEY（CRT 可为 DER 编码）或带密码的 P12/PFX。含中间证书的 PEM 使用完整链，私有 CA 可随证书一起更新。证书链、所有配置 SAN 和私钥配对通过验证后，同时热更新推理和管理监听器。已有连接继续使用各自已建立的 TLS 会话。轮换失败时保留原证书。页面展示 SHA-256 指纹和链有效期，在剩余 30 天内给出预警。

「生成并下载 CSR」在网关中生成加密保存的私钥，仅下载 CSR。将签发后的叶证书及中间链上传，并填入对应 CSR 标识。导出客户端 CA 只包含公共证书。页面修改客户端公布端口时，需要与宿主端口映射或入口实际端口一致。修改监听端口可在 Compose 更新配置并重启。

私钥、证书库和加密根密钥要单独备份。首次启动后可从启动目录移除私钥，保留空挂载目录。若证书已经过期到客户端无法进入管理面板，将有效证书放入只读启动目录，在 `.env` 临时设置 `TLS_FORCE_BOOTSTRAP=true` 并重建容器实例。验证服务恢复后移除此变量。

## Profile

默认使用 Generic OpenAI Compatible。配置中的 Bearer Key 决定客户端 ID、可访问模型和 Profile。例如：

```dotenv
GATEWAY_CLIENTS='[{"id":"java-runtime","key":"<独立网关密钥>","models":["qwen3.6-35b-a3b"],"profile":"langchain4j"}]'
```

Profile 标识为 `generic`、`cline`、`langchain4j`、`python` 和 `node`。五个预设默认遵循同一标准协议。只有根据实测差异在「兼容规则」发布特定模型和 Profile 的规则后才生效。规则按「所有客户端共用」在前、当前 Profile 专属规则在后的顺序运行。请求头或 User-Agent 不改变已授权的 Profile。

## Cline 模型别名

新增模型映射时，从 Cline 4.1.27 随包离线目录搜索并选择对外模型，例如 `qwen3.6-35b-a3b`。后台保存客户真实模型 ID（例如 `/models/Qwen3.6-35B-A3B`）和对应上游。应用和 Cline 通过 `/v1/models` 只发现已发布且有权访问的映射。在 SDK 中继续使用选定的对外 ID。同一个对外 ID 只绑定一个映射，现有目录之外的历史别名可以继续编辑。目录仅提供标识，模型能力仍由实际上游决定。

目录从项目实际使用的 `cline-agent-server:4.1.27-local` 镜像内置模型目录提取，来源版本和提交保存在 `web/cline-models.json`。升级 Cline 时，可将 `scripts/extract-cline-models.cjs` 挂载进目标 Cline 镜像并运行 Node 导出，复核差异后随新版镜像发布。

实验室使用真实推理监听器和已授权的服务端测试身份完成 TLS 与 Bearer 认证，显示标准请求、TLS/认证、上游请求、上游响应、标准响应以及协议检查。可以选择 Profile 做对照，或导入脱敏的 JSON body、`{body,headers,profile}` 样本；界面移除认证类请求头。通过页面发起的诊断是同一条请求，服务端不会为采集轨迹重复生成。通过「直连上游」模式可对照客户真实响应。协议 PASS 表示收到可解析的标准响应，工具和业务产物以实测运行结果为准。

## 客户端配置

- **Cline Native SDK** 使用 OpenAI Compatible、HTTPS Base URL、发布模型 ID 和网关 Bearer Key。当前集成的 Cline 4.1.27 容器可把客户 CA 合并至容器系统 CA bundle，以 `node --use-system-ca ...` 启动宿主服务。Node 原生子进程继承启动参数。通用 Node SDK 或由环境直接启动的 Cline 可在进程启动前设置 `NODE_EXTRA_CA_CERTS=/path/to/gateway-ca.crt`。
- **LangChain4j** 的 `OpenAiChatModel` 和 `OpenAiStreamingChatModel` 使用相同的 `baseUrl`、`modelName` 和 `apiKey`。当前项目的版本是 `langchain4j-open-ai:1.20.0`。用 `scripts/import-java-ca.sh` 或 `scripts/import-java-ca.ps1` 将 CA 导入新的 PKCS12 TrustStore。设置 `JAVA_TRUSTSTORE_PASSWORD` 后，脚本接受 CA bundle 与目标文件。将 TrustStore 和其密码交给 JVM 的 `javax.net.ssl.trustStore`、`javax.net.ssl.trustStoreType=PKCS12` 和 `javax.net.ssl.trustStorePassword` 配置。如果同一 JVM 还访问公共 API，将企业 CA 导入保留了原有信任根的应用专用 TrustStore。
- **OpenAI Python SDK** 可配置 `SSL_CERT_FILE`，或使用 `DefaultHttpx2Client(verify=ssl.create_default_context(cafile=...))` 指定受信任 CA。
- **OpenAI Node.js SDK** 在启动进程前设置 `NODE_EXTRA_CA_CERTS`，用 `baseURL`、`apiKey` 连接网关。
- **curl** 使用 `--cacert gateway-ca.crt` 和 `Authorization: Bearer $GATEWAY_API_KEY`。

标准 JSON 和 SSE 接口路径为 `/v1/chat/completions`。可用 `/v1/models` 验证当前 Key 可见的已发布模型。

在「接入配置」页面可选择已启用的 HTTP 或 HTTPS 入口并复制对应 Base URL、模型 ID 和示例。通过 HTTPS 登录管理台后，点击「查看 Key」可显示并复制所选模型和客户端关联的网关 Key。SDK 和 curl 示例通过 `GATEWAY_API_KEY` 环境变量引用凭据。
