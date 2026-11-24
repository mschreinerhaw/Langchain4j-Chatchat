# HTTPS 与 SDK 验收报告

## 1.1.1 自签证书与模型选择

本地 Docker 从空证书目录启动，离线生成自签服务器证书，经宿主导出公开证书。网关经过白名单连接自签 HTTPS Mock 上游。原生客户端成功调用，未信任证书或名称不匹配时被拒绝。轮换后旧信任文件失效，新公开证书可以连接。传统 CA 链回归测试在 Docker 内部网络通过。

Cline 4.1.27 通过受信任的自签 HTTPS 完成两轮流式推理并调用原生 `editor`，独立检查了工作区文件。LangChain4j 1.20.0 导入自签服务器证书至 PKCS12 TrustStore 后，普通对话、中文 SSE 和原生工具回传通过。Playwright 从离线 Cline 目录搜索并发布 `qwen3.6-35b-a3b`，随后通过 `/v1/models` 及 Chat Completions 验证该对外 ID。Mock 观测到其独立的真实上游 ID。

## 1.1.0 HTTPS 验收

验收环境为本地 Docker Desktop Linux/amd64。镜像 `llm-compatibility-gateway:1.1.0` 以 `node` 用户运行。用独立 Docker 网络、离线签发的根 CA 和中间 CA 验证。以下 SDK 均实际连接容器中的网关标准 API，模型侧使用可重复的 Mock Upstream。

| 验收项 | 实测结果 |
| --- | --- |
| TLS 1.2 / TLS 1.3、DNS 与 IP SAN、自定义入口端口 | 通过。客户端分别协商 TLS 1.2 和 TLS 1.3。容器端口 8445、宿主端口 8443。 |
| 证书信任与拒绝 | 通过。信任所配根且服务器发送中间证书时连接成功。缺少受信任根、错误根、SAN 不匹配和过期证书的客户端连接被拒绝。导入错误私钥、过期证书和缺失中间链也被拒绝。 |
| 私有 CA、P12、CSR、证书热更新 | 通过。导出私有 CA，导入带密码的 P12，并完成 CSR 生成、离线签发和部署。错误更新保留原指纹。成功轮换后的新指纹同时出现在推理与管理入口，容器重启后仍保持生效。20 天有效期证书触发预警。 |
| HTTPS → HTTP、HTTPS → HTTPS | 通过。两种上游都返回 JSON/SSE。上游 HTTPS 缺少受信任私有根时网关拒绝连接。 |
| HTTP → HTTP、HTTP → HTTPS | 通过受控明文入口验证。生产 Compose 默认只发布两个 HTTPS 端口。 |
| JSON/SSE 转换、Profile 和工具分片 | 通过。通用规则和 LangChain4j Profile 定向生效，测试密钥不能访问授权范围外的模型。内容、工具调用 ID 和参数片段经真实 HTTPS 转发。 |
| 管理台、CA 下载、脱敏样本回放 | Playwright 实测通过。先加载证书页面及公共 CA，再导入样本并显示实际 TLS、Bearer 认证、Profile 与上下游轨迹。 |
| Cline Native SDK 4.1.27 | 通过 Mock 业务任务。原生 `editor` 工具生成 `gateway-result.txt`，随后回传工具结果并完成第二次流式推理。任务状态为 `turn_completed`。独立读取容器内文件，内容为 `HTTPS gateway verified`。 |
| LangChain4j 1.20.0 | 通过真实 SDK 运行。JVM 通过 PKCS12 TrustStore 校验网关签名，验证普通对话、中文 SSE、普通工具调用、流式工具参数拼接，以及工具结果回传所得业务值 `SDK_TASK_ARTIFACT_258`。项目根 Maven POM 使用同一版本。 |
| OpenAI Python SDK 3.58.0 | 通过模型发现、JSON 对话与中文 SSE。客户端加载指定 CA。 |
| OpenAI Node.js SDK 7.63.0 | 通过模型发现、JSON 对话、SSE 工具分片和工具结果回传。Node 信任通过 `NODE_EXTRA_CA_CERTS` 配置。 |
| 完全断网运行 | 使用 Docker `Internal=true` 的无外网网络启动网关及两个 Mock，通过 HTTPS 运行合同测试。HTTPS 到 HTTP 和 HTTPS 两条链路均成功。Windows 离线安装脚本完成归档校验、镜像加载和 Compose 启动，两个经过证书验证的 HTTPS 端点均返回 200。 |

Cline 验收实际观测到 `permission.requested`、`permission.decision`、`tool.started`、`tool.completed` 和 `turn.completed`。网关记录两次 `cline` Profile 的成功 SSE 推理请求。浏览器的独立测试仅对测试叶证书 SPKI 配置临时例外。TLS 信任、SAN、证书拒绝和 SDK 请求由原生客户端分别验证。

- [证书管理截图](https-certificates.png)
- [HTTPS 请求回放截图](https-replay.png)

## 现场验收边界

开发容器再次探测客户上游 `10.2.25.111:32004`，TCP 超时。客户证书、客户模型真实推理、客户 Cline 与 LangChain4j 业务任务仍需在可达环境用授权凭据验收。24 小时连续稳定性也待验证。上述 Mock 测试验证的是网关与实际 SDK 的协议和工具工作流。

## 复现入口

1. 构建镜像，运行 `tests/create-tls-fixtures.sh`，用 Docker 挂载当前文件到 `/fixture.sh`、`local/tls` 到 `/out`，通过镜像的 `sh /fixture.sh` 入口完成签发。证书与私钥只保存在忽略的 `local/tls` 下。
2. 在隔离目录将 `tests/tls-fixture.env` 复制为 `.env`。启动 `docker compose -p llm-gateway-tlstest -f docker-compose.yml -f tests/compose.tls.yml up --no-build --pull never -d --wait`。可显式添加 `-f docker-compose.http-test.yml` 测试 HTTP 回环入口。
3. 执行 `npm run test:tls`。在提供 Docker CLI 的宿主上可设置 `TEST_SIGN_CSR=1` 运行 CSR 签发部署测试。明文入口启用时设 `TEST_HTTP_PORT=18080` 验证 HTTP 到 HTTPS。离线验证时加 `tests/compose.offline.yml`，从相同内部网络的测试容器以 `TEST_TLS_HOST=llm-gateway`、`TEST_TLS_PORT=8445` 调用。
4. 在测试网络运行 Cline 容器，将 `tests/cline-entrypoint.sh` 挂载到 `/fixture.sh`，测试 CA 挂载到 `/etc/ssl/certs/ca-certificates.crt`，使用 `CLINE_FIXTURE_TLS=1` 和 `--entrypoint sh … /fixture.sh` 启动，回环发布 18329。设置 `TEST_ADMIN=https://127.0.0.1:18081` 和 `NODE_EXTRA_CA_CERTS`，运行 `node tests/cline-smoke.mjs`。独立文件验证默认使用容器名 `llm-gateway-cline-https`。
5. SDK 验收程序位于 `tests/sdk`。使 `GATEWAY_BASE_URL` 指向 HTTPS 推理入口，并使用适用的 `SDK_GATEWAY_KEY`。Java 依赖使用 `tests/sdk/pom.xml`，Java 主类为 `GatewaySdkAcceptance`。Python 通过 `GATEWAY_TEST_CA` 读取 CA，Node 在解释器启动时读取 `NODE_EXTRA_CA_CERTS`。浏览器测试以 `TEST_ADMIN` 和测试叶证书 SHA-256 SPKI 的 Base64 值 `TEST_TLS_SPKI` 运行 `tests/admin-https.spec.ts`。
