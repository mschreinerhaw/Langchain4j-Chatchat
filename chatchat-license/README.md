# ChatChat License Center

`chatchat-license` 同时提供可复用的离线验签核心，以及仅供企业内部部署的 License
Center 管理服务。客户的 `chatchat-mcp-server` 只依赖验签核心，不包含私钥、签发接口或
授权文件生成页面。

## 授权流程

1. 客户在 MCP 管理端的“License 授权信息”页面复制目标服务器 MAC 地址。
2. 企业授权人员登录独立 License Center，填写客户、MAC、模块、功能和有效期。
3. License Center 使用 RSA 私钥生成并下载 `license.dat`。
4. 将授权文件交付客户，部署到 MCP Server 配置的 `CHATCHAT_LICENSE_FILE` 路径。
5. MCP Server 使用公钥验签，并检查本机网卡是否包含 License 绑定的 MAC。

## 生成密钥

```shell
openssl genpkey -algorithm RSA -out license-private.pem -pkeyopt rsa_keygen_bits:3072
openssl pkey -in license-private.pem -pubout -out license-public.pem
```

私钥只部署在企业内部 License Center。客户环境只交付公钥。

## 启动企业内部 License Center

```text
CHATCHAT_LICENSE_PRIVATE_KEY_PATH=/secure/license-private.pem
CHATCHAT_LICENSE_PUBLIC_KEY_PATH=/secure/license-public.pem
CHATCHAT_LICENSE_AUTO_GENERATE_KEYS=true
CHATCHAT_LICENSE_KEY_ID=prod-2026
CHATCHAT_LICENSE_CENTER_USERNAME=license-admin
CHATCHAT_LICENSE_CENTER_PASSWORD=<strong-password>
CHATCHAT_LICENSE_CENTER_PORT=8092
```

```shell
java -jar chatchat-license-1.0.0-SNAPSHOT-server.jar
```

访问 `http://localhost:8092/`，登录后即可根据客户 MAC 生成授权文件。服务未配置管理密码
时会拒绝启动。

## 客户 MCP Server 配置

```text
CHATCHAT_LICENSE_PUBLIC_KEY_PATH=/opt/livemcp/license-public.pem
CHATCHAT_LICENSE_FILE=/opt/livemcp/license.dat
CHATCHAT_LICENSE_FAIL_STARTUP_ON_INVALID=false
CHATCHAT_LICENSE_STATUS_CHECK_INTERVAL_MS=60000
```

客户管理端只提供 `GET /api/v1/license/status`，用于查看授权情况、本机机器码和 MAC 地址。
不存在上传、签发或下载 License 的客户侧接口。

## 授权到期行为

默认采用可运维的“受限模式”：

- 服务继续运行，License 状态页和管理端仍可访问，客户能够查看 MAC、过期原因并申请续期。
- 到期后的新 `tools/call` 请求立即返回 `403`，不会继续进入角色授权或工具执行阶段。
- 已经完成的历史结果和审计记录不会删除；正在执行且已通过入口校验的调用不会被强制中断。
- 服务每 60 秒检查一次 License 状态并记录状态变化；每次工具调用也会实时校验，因此不依赖定时任务才生效。
- 客户替换有效的 `license.dat` 后，后续调用可自动恢复，无需重启服务。

如果部署要求 License 无效时禁止服务启动，可设置：

```text
CHATCHAT_LICENSE_FAIL_STARTUP_ON_INVALID=true
```

该严格模式会使过期或无效 License 阻止 MCP Server 启动，因此续期时需要通过文件系统替换
`license.dat`，不能依赖客户管理页面。

## 安全模型

- 使用 `SHA256withRSA` 签名，修改授权内容会导致验签失败。
- License 绑定标准化 MAC，例如 `MAC-AABBCCDDEEFF`。
- 运行节点会比对当前非回环网卡，不信任可复制的本地 `server.id` 文件。
- 安装文件过期、尚未生效、签名错误或 MAC 不匹配时均不可用。
- License Center 使用独立登录认证，应部署在企业内网，不对客户网络开放。
