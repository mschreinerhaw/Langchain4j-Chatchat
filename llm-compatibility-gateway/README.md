# LLM Compatibility Gateway — 私有化大模型标准接入网关

独立的 Node.js 24 / TypeScript 网关。一个镜像提供原生 TLS 1.2/1.3、OpenAI Compatible 推理、模型别名、JSON/SSE/Tool Calling 适配和本地 React 管理台。Cline、LangChain4j 和其他 OpenAI 兼容客户端可使用同一个标准 HTTPS 入口。上游可独立使用 HTTP 或 HTTPS。

## 本地 Docker 开发

在本目录复制 `.env.example` 为 `.env`，填写独立的管理员密码、网关 Key、32 字节十六进制配置加密密钥和允许的上游 Origin。可用 `node -p "require('node:crypto').randomBytes(32).toString('hex')"` 生成各个值。将服务证书、私钥和必要的私有 CA 放在启动证书目录，填写证书 SAN 对应的 `TLS_SERVER_NAMES`。

```sh
docker compose up --build -d --wait
docker compose ps
```

推理 API 默认为 `https://<证书域名>:8443/v1`，管理台为 `https://<证书域名>:18081`。登录后新增上游服务与模型映射。配置发布立即生效。

## 首个客户案例

| 配置 | 值 |
| --- | --- |
| Origin | `http://10.2.25.111:32004` |
| 对话路径 | `/v1/chat/completions` |
| 真实模型 ID | `/models/Qwen3.6-35B-A3B` |
| 对外模型 ID | `qwen3.6-35b-a3b` |
| Cline Provider | OpenAI Compatible |
| Cline Base URL | `https://<证书域名>:8443/v1` |

上游凭据在管理页面录入。客户端填写独立网关 Key。通过管理页的「接入配置」生成 SDK 示例。在另一个容器中使用 Cline 时，连接可达的网关主机或同一 Docker 网络中的网关服务名。

## 工程文档

- [架构、后台操作与规则配置](docs/operations.md)
- [HTTPS 证书、Profile 与 SDK 接入](docs/https.md)
- [OpenAPI 3.1](docs/openapi.yaml)
- [HTTPS 与实际 SDK 验收](docs/https-verification.md)
- [1.0 版协议与性能基线](docs/verification.md)

运行中的静态资源包含在镜像内。多协议接口返回 501。各框架接入使用该框架的 OpenAI-compatible 端点。
