# LLM 兼容网关

独立的 Node.js 24 / TypeScript 网关。Fastify 同时提供 OpenAI Chat Completions 与本地 React 管理台。一个镜像即可部署。Langchain4j-Chatchat、Runtime OS 和 Cline 通过 OpenAI Compatible Base URL 接入。

## 本地 Docker 开发

在本目录复制 `.env.example` 为 `.env`，填写独立的管理员密码、网关 Key、32 字节十六进制配置加密密钥和允许的上游 Origin。可用 `node -p "require('node:crypto').randomBytes(32).toString('hex')"` 生成各个值。

```sh
docker compose up --build -d --wait
docker compose ps
```

推理 API 为 `http://127.0.0.1:18080/v1`，管理员在 `http://127.0.0.1:18081` 登录。登录后新增上游服务与模型映射。发布立即生效。

## 首个客户案例

| 配置 | 值 |
| --- | --- |
| Origin | `http://10.2.25.111:32004` |
| 对话路径 | `/v1/chat/completions` |
| 真实模型 ID | `/models/Qwen3.6-35B-A3B` |
| 对外模型 ID | `qwen3.6-35b-a3b` |
| Cline Provider | OpenAI Compatible |
| Cline Base URL | `http://<网关地址>:18080/v1` |

上游凭据在管理页面录入。客户端填写独立网关 Key。通过管理页的「接入配置」生成 SDK 示例。在另一个容器中使用 Cline 时，连接可达的网关主机或同一 Docker 网络中的网关服务名。

## 工程文档

- [架构、后台操作与规则配置](docs/operations.md)
- [OpenAPI 3.1](docs/openapi.yaml)
- [验收与性能报告](docs/verification.md)

运行中的静态资源包含在镜像内。多协议接口返回 501。各框架接入使用该框架的 OpenAI-compatible 端点。
