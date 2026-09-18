# ChatChat 通过 Nginx 访问 SGLang 内网模型部署指南

文档版本：1.1  
适用环境：ChatChat、JDK 17、LangChain4j、SGLang/Uvicorn、OpenAI 兼容接口  
目标问题：模型服务返回 `400 Field required`、`loc: ('body',)`、`input: None`

## 1. 背景与结论

ChatChat 使用 JDK HTTP Client 调用 SGLang 的 OpenAI 兼容接口时，客户端可能携带 HTTP/2 cleartext upgrade（h2c）或使用分块请求。部分 Uvicorn/httptools 组合能够收到 `POST /v1/chat/completions`，但无法把请求体交给 FastAPI，最终返回类似错误：

```text
InvalidRequestException
1 validation error
loc: ('body',)
msg: Field required
input: None
```

本方案不升级 JDK，在 ChatChat 与模型服务之间部署 Nginx。Nginx 完整接收并缓冲请求体，清除 h2c 相关请求头，再通过普通 HTTP/1.0 请求转发至 SGLang。

当前增强版 ChatChat 同时兼容以下配置形式：

- 前端展示名，例如 `DeepSeek-V4.1-Flash`。
- Spring 方括号 Map 键，例如 `"[DeepSeek-V4.1-Flash]"`。
- 显式配置的服务真实模型 ID，例如 `/models/DeepSeek-V4.1-Flash`。
- 大小写不同但内容相同的展示名或真实模型 ID。
- OpenAI 基础地址 `/v1` 和完整地址 `/v1/chat/completions`。
- `application*.yml` 中的模型配置。
- `config/env.properties` 中的 Spring 属性配置。

模型解析优先级为：配置键精确匹配、配置键忽略大小写匹配、显式 `modelName` 精确匹配、显式 `modelName` 忽略大小写匹配、有效 legacy OpenAI 配置。系统不会根据 `/models/`、厂商名称或端口猜测模型关系。找不到连接时不再静默回退到空配置，错误会包含选中模型、匹配配置键和已配置模型列表，但不会输出 API Key。

部署链路如下：

```text
ChatChat（JDK 17）
        |
        | HTTP，OpenAI 兼容请求
        v
应用主机 Nginx
  - 缓冲请求体
  - 清除 Upgrade/HTTP2-Settings
  - 使用 HTTP/1.0 访问上游
        |
        v
SGLang / Uvicorn 内网模型服务
```

## 2. 本次端点规划

Nginx 默认安装在 ChatChat 所在服务器，并仅监听本机回环地址。端口映射如下：

| 模型 | 实际上游 | Nginx 本地地址 |
| --- | --- | --- |
| Qwen3-32B | `10.6.65.11:30000` | `127.0.0.1:31000` |
| Qwen3.8-27B | `10.6.65.11:30002` | `127.0.0.1:31002` |
| DeepSeek-V4.1-Flash | `10.6.65.11:30005` | `127.0.0.1:31005` |
| DeepSeek-V4-Flash-int8 | `10.6.65.11:30002` | `127.0.0.1:31002` |
| Qwen3.6-35B-A3B | `10.6.65.1:30004` | `127.0.0.1:32004` |
| Qwen3-VL-8B-Instruct | `10.6.65.1:30002` | `127.0.0.1:32002` |
| Qwen3-32B-A3B | `10.6.65.1:30000` | `127.0.0.1:32000` |

部署前必须确认：

1. `10.6.65.1` 与 `10.6.65.11` 是否确实为两台不同服务器。
2. `10.6.65.11:30002` 是否同时提供 Qwen3.8-27B 和 DeepSeek-V4-Flash-int8。
3. 每个服务的真实模型 ID 必须以其 `/v1/models` 返回的 `data[].id` 为准。

## 3. 部署前检查

### 3.0 内网模型信息不完整时如何处理

内网模型服务经常只提供一个 IP、端口和聊天地址，展示名称、真实模型 ID、鉴权方式及协议细节可能不完整。不要根据展示名称猜测全部配置，按以下顺序探测：

1. 确认 TCP 端口可达。
2. 请求 `/v1/models` 获取真实模型 ID。
3. 使用返回的 ID 直接调用 `/v1/chat/completions`。
4. curl 成功后再接入 Nginx。
5. Nginx curl 成功后再修改 ChatChat `baseUrl`。

探测结果与配置方式：

| 服务表现 | ChatChat 配置方式 |
| --- | --- |
| 返回 `id: Qwen3-32B` | `modelName: Qwen3-32B` |
| 返回 `id: /models/Qwen3-32B` | 原样配置 `modelName: /models/Qwen3-32B` |
| `/v1/models` 返回多个 ID | 为每个 ID 建立一个 `chatModels` 项，可以共用同一个 `baseUrl` |
| `/v1/models` 为 `401/403` | 加 Bearer Token 后重试 |
| 服务不校验 Token | 仍配置一个非空占位值，例如由环境变量注入的 `no-key` |
| 给出完整 `/v1/chat/completions` 地址 | 可以直接配置；增强版会归一化为 OpenAI base URL，生产配置仍建议写到 `/v1` |
| 模型名称随部署变化 | 保持前端展示键稳定，只更新对应项的 `modelName` |

不要把聊天工具自动生成的 Markdown 链接复制进 YAML。代码只接受明确的绝对 HTTP(S) URL，不猜测富文本含义。以下写法无效：

```yaml
baseUrl: [http://127.0.0.1:31005/v1](http://127.0.0.1:31005/v1)
```

必须改成普通 YAML 字符串：

```yaml
baseUrl: "http://127.0.0.1:31005/v1"
```

### 3.1 从应用主机检查上游连通性

```bash
curl --noproxy "*" -sv --connect-timeout 5 http://10.6.65.11:30000/v1/models
curl --noproxy "*" -sv --connect-timeout 5 http://10.6.65.11:30002/v1/models
curl --noproxy "*" -sv --connect-timeout 5 http://10.6.65.11:30005/v1/models
curl --noproxy "*" -sv --connect-timeout 5 http://10.6.65.1:30000/v1/models
curl --noproxy "*" -sv --connect-timeout 5 http://10.6.65.1:30002/v1/models
curl --noproxy "*" -sv --connect-timeout 5 http://10.6.65.1:30004/v1/models
```

预期至少获得 HTTP 响应。常见结果：

- `200`：地址和端口正常。
- `401/403`：网络正常，需要正确 Token。
- `Connection refused`：上游未监听或防火墙主动拒绝。
- `Connection timed out`：路由、防火墙或 ACL 不通。

### 3.2 查询真实模型 ID

以下示例使用环境变量，避免 Token 进入 shell 历史之外的配置文本：

```bash
export CHATCHAT_INTERNAL_MODEL_API_KEY='替换为实际Token'

curl -sS --noproxy "*" \
  -H "Authorization: Bearer ${CHATCHAT_INTERNAL_MODEL_API_KEY}" \
  http://10.6.65.11:30000/v1/models
```

例如服务返回：

```json
{
  "object": "list",
  "data": [
    {
      "id": "/models/Qwen3-32B",
      "object": "model"
    }
  ]
}
```

则 ChatChat 中必须配置：

```yaml
modelName: /models/Qwen3-32B
```

不能自行简写为 `Qwen3-32B`。

## 4. 安装 Nginx

### 4.1 RHEL、Rocky Linux、AlmaLinux、CentOS

```bash
sudo dnf install -y nginx
sudo systemctl enable nginx
```

旧版系统可以使用：

```bash
sudo yum install -y nginx
sudo systemctl enable nginx
```

### 4.2 Ubuntu、Debian

```bash
sudo apt-get update
sudo apt-get install -y nginx
sudo systemctl enable nginx
```

### 4.3 检查安装结果

```bash
nginx -v
sudo nginx -t
```

## 5. 部署完整 Nginx 配置

创建 `/etc/nginx/conf.d/chatchat-llm-proxy.conf`：

```nginx
# ChatChat -> SGLang OpenAI-compatible proxy
# 默认仅监听回环地址，不向局域网公开代理端口。

log_format chatchat_llm
    '$remote_addr [$time_local] "$request" status=$status '
    'request_length=$request_length request_time=$request_time '
    'upstream=$upstream_addr upstream_status=$upstream_status '
    'upstream_connect_time=$upstream_connect_time '
    'upstream_response_time=$upstream_response_time';

# Qwen3-32B -> 10.6.65.11:30000
server {
    listen 127.0.0.1:31000;
    server_name _;

    access_log /var/log/nginx/chatchat-llm-31000.access.log chatchat_llm;
    error_log  /var/log/nginx/chatchat-llm-31000.error.log warn;
    client_max_body_size 100m;

    location / {
        proxy_pass http://10.6.65.11:30000;
        proxy_http_version 1.0;
        proxy_request_buffering on;
        proxy_buffering off;

        proxy_set_header Host $proxy_host;
        proxy_set_header Connection "";
        proxy_set_header Upgrade "";
        proxy_set_header HTTP2-Settings "";

        proxy_connect_timeout 10s;
        proxy_send_timeout 1800s;
        proxy_read_timeout 1800s;
    }
}

# Qwen3.8-27B、DeepSeek-V4-Flash-int8 -> 10.6.65.11:30002
server {
    listen 127.0.0.1:31002;
    server_name _;

    access_log /var/log/nginx/chatchat-llm-31002.access.log chatchat_llm;
    error_log  /var/log/nginx/chatchat-llm-31002.error.log warn;
    client_max_body_size 100m;

    location / {
        proxy_pass http://10.6.65.11:30002;
        proxy_http_version 1.0;
        proxy_request_buffering on;
        proxy_buffering off;

        proxy_set_header Host $proxy_host;
        proxy_set_header Connection "";
        proxy_set_header Upgrade "";
        proxy_set_header HTTP2-Settings "";

        proxy_connect_timeout 10s;
        proxy_send_timeout 1800s;
        proxy_read_timeout 1800s;
    }
}

# DeepSeek-V4.1-Flash -> 10.6.65.11:30005
server {
    listen 127.0.0.1:31005;
    server_name _;

    access_log /var/log/nginx/chatchat-llm-31005.access.log chatchat_llm;
    error_log  /var/log/nginx/chatchat-llm-31005.error.log warn;
    client_max_body_size 100m;

    location / {
        proxy_pass http://10.6.65.11:30005;
        proxy_http_version 1.0;
        proxy_request_buffering on;
        proxy_buffering off;

        proxy_set_header Host $proxy_host;
        proxy_set_header Connection "";
        proxy_set_header Upgrade "";
        proxy_set_header HTTP2-Settings "";

        proxy_connect_timeout 10s;
        proxy_send_timeout 1800s;
        proxy_read_timeout 1800s;
    }
}

# Qwen3-32B-A3B -> 10.6.65.1:30000
server {
    listen 127.0.0.1:32000;
    server_name _;

    access_log /var/log/nginx/chatchat-llm-32000.access.log chatchat_llm;
    error_log  /var/log/nginx/chatchat-llm-32000.error.log warn;
    client_max_body_size 100m;

    location / {
        proxy_pass http://10.6.65.1:30000;
        proxy_http_version 1.0;
        proxy_request_buffering on;
        proxy_buffering off;

        proxy_set_header Host $proxy_host;
        proxy_set_header Connection "";
        proxy_set_header Upgrade "";
        proxy_set_header HTTP2-Settings "";

        proxy_connect_timeout 10s;
        proxy_send_timeout 1800s;
        proxy_read_timeout 1800s;
    }
}

# Qwen3-VL-8B-Instruct -> 10.6.65.1:30002
server {
    listen 127.0.0.1:32002;
    server_name _;

    access_log /var/log/nginx/chatchat-llm-32002.access.log chatchat_llm;
    error_log  /var/log/nginx/chatchat-llm-32002.error.log warn;
    client_max_body_size 100m;

    location / {
        proxy_pass http://10.6.65.1:30002;
        proxy_http_version 1.0;
        proxy_request_buffering on;
        proxy_buffering off;

        proxy_set_header Host $proxy_host;
        proxy_set_header Connection "";
        proxy_set_header Upgrade "";
        proxy_set_header HTTP2-Settings "";

        proxy_connect_timeout 10s;
        proxy_send_timeout 1800s;
        proxy_read_timeout 1800s;
    }
}

# Qwen3.6-35B-A3B -> 10.6.65.1:30004
server {
    listen 127.0.0.1:32004;
    server_name _;

    access_log /var/log/nginx/chatchat-llm-32004.access.log chatchat_llm;
    error_log  /var/log/nginx/chatchat-llm-32004.error.log warn;
    client_max_body_size 100m;

    location / {
        proxy_pass http://10.6.65.1:30004;
        proxy_http_version 1.0;
        proxy_request_buffering on;
        proxy_buffering off;

        proxy_set_header Host $proxy_host;
        proxy_set_header Connection "";
        proxy_set_header Upgrade "";
        proxy_set_header HTTP2-Settings "";

        proxy_connect_timeout 10s;
        proxy_send_timeout 1800s;
        proxy_read_timeout 1800s;
    }
}
```

配置中的关键指令如下：

| 指令 | 作用 |
| --- | --- |
| `proxy_http_version 1.0` | 避免向 Uvicorn 转发 h2c/chunked 请求 |
| `proxy_request_buffering on` | Nginx 完整接收请求体后再访问模型服务 |
| `proxy_set_header Upgrade ""` | 删除协议升级头 |
| `proxy_set_header HTTP2-Settings ""` | 删除 HTTP/2 cleartext 升级参数 |
| `proxy_buffering off` | 避免缓存模型响应，兼容流式响应 |
| `proxy_read_timeout 1800s` | 允许长时间模型推理 |

不要在 access log 中输出 `$http_authorization` 或请求正文。

## 6. 启用 Nginx

检查配置：

```bash
sudo nginx -t
```

预期输出：

```text
syntax is ok
test is successful
```

启动或重新加载：

```bash
sudo systemctl start nginx
sudo systemctl reload nginx
sudo systemctl status nginx --no-pager
```

检查监听端口：

```bash
ss -lntp | grep nginx
```

应看到 `127.0.0.1:31000`、`31002`、`31005`、`32000`、`32002` 和 `32004`。

### 6.1 SELinux 环境

如果系统启用了 SELinux，Nginx 可能被禁止访问内网模型端口。执行：

```bash
getenforce
sudo setsebool -P httpd_can_network_connect 1
```

如果出现 `502 Bad Gateway`，同时检查：

```bash
sudo tail -n 100 /var/log/audit/audit.log
sudo tail -n 100 /var/log/nginx/chatchat-llm-31000.error.log
```

### 6.2 防火墙

默认配置只监听 `127.0.0.1`，不需要对外开放 31xxx/32xxx 端口。

不得直接向全网开放这些模型代理端口。如果确实需要跨主机访问，应绑定指定内网地址，并使用 firewalld、iptables 或安全组限制来源地址。

## 7. 验证 Nginx 转发

### 7.1 验证模型列表

```bash
curl -sS \
  -H "Authorization: Bearer ${CHATCHAT_INTERNAL_MODEL_API_KEY}" \
  http://127.0.0.1:31000/v1/models
```

### 7.2 验证聊天请求

```bash
curl -sv http://127.0.0.1:31000/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${CHATCHAT_INTERNAL_MODEL_API_KEY}" \
  --data '{
    "model": "/models/Qwen3-32B",
    "messages": [
      {"role": "user", "content": "你好，请只回复：代理正常"}
    ],
    "stream": false
  }'
```

验收标准：

- 不再出现 `loc: ('body',)`、`input: None`。
- HTTP 状态为 `200`。
- 响应包含 `choices`。
- Nginx access log 中 `request_length` 大于请求头长度，且 `upstream_status=200`。

查看日志：

```bash
sudo tail -f /var/log/nginx/chatchat-llm-31000.access.log
sudo tail -f /var/log/nginx/chatchat-llm-31000.error.log
```

## 8. ChatChat 完整模型配置

修改实际生效的 `config/application-dev.yml` 或生产 Profile 对应的外部配置。不要只修改源码资源文件后继续运行旧部署包。

配置键使用稳定的前端显示名；`modelName` 使用 `/v1/models` 返回的真实 ID。带 `.`、`/` 或其他特殊字符的 Spring Map 键统一使用带引号的方括号格式：

```yaml
chatchat:
  models:
    defaultChatModel: Qwen3-32B

    context-window-max-tokens: 200000
    context-reserved-system-tokens: 20000
    context-reserved-history-tokens: 30000
    context-reserved-output-tokens: 30000

    availableChatModels:
      - Qwen3-32B
      - Qwen3.8-27B
      - DeepSeek-V4.1-Flash
      - DeepSeek-V4-Flash-int8
      - Qwen3.6-35B-A3B
      - Qwen3-VL-8B-Instruct
      - Qwen3-32B-A3B

    chatModels:
      "[Qwen3-32B]":
        modelName: /models/Qwen3-32B
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:31000/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1

      "[Qwen3.8-27B]":
        modelName: /models/Qwen3.8-27B
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:31002/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1

      "[DeepSeek-V4.1-Flash]":
        modelName: /models/DeepSeek-V4.1-Flash
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:31005/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1

      "[DeepSeek-V4-Flash-int8]":
        modelName: /models/DeepSeek-V4-Flash-int8
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:31002/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1

      "[Qwen3.6-35B-A3B]":
        modelName: /models/Qwen3.6-35B-A3B
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:32004/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1

      "[Qwen3-VL-8B-Instruct]":
        modelName: /models/Qwen3-VL-8B-Instruct
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:32002/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1

      "[Qwen3-32B-A3B]":
        modelName: /models/Qwen3-32B-A3B
        apiKey: ${CHATCHAT_INTERNAL_MODEL_API_KEY}
        baseUrl: http://127.0.0.1:32000/v1
        protocol: openai
        timeout: 1800000
        maxTokens: -1
        maxRetries: 1
```

如果某个 `/v1/models` 返回的 ID 与示例不同，必须修改对应 `modelName`，不能修改成仅用于前端展示的名称。

增强版代码也允许前端或任务载荷传入真实模型 ID。例如配置键为 `DeepSeek-V4.1-Flash`、`modelName` 为 `/models/DeepSeek-V4.1-Flash` 时，以下两个选择值都会解析到同一连接：

```text
DeepSeek-V4.1-Flash
/models/DeepSeek-V4.1-Flash
```

推荐仍由前端发送稳定展示名，避免模型服务迁移路径后影响历史任务。

### 8.1 Nginx 与 ChatChat 不在同一台服务器

只有 Nginx 和 ChatChat 运行在同一网络命名空间时才能使用 `127.0.0.1`。例如 ChatChat 在 `10.2.25.112`、Nginx 在 `10.2.25.111` 时：

1. Nginx 应监听 `10.2.25.111:31000`，不能只监听 `127.0.0.1:31000`。
2. ChatChat 应配置 `baseUrl: http://10.2.25.111:31000/v1`。
3. 防火墙仅允许 `10.2.25.112` 访问代理端口。

出现 `ConnectException` 或 `ClosedChannelException` 时，先检查 ChatChat 到 Nginx 的 TCP 连接，不要继续调整模型 ID。

### 8.2 使用 config/env.properties 配置模型

除了 `application-dev.yml`、`application-prod.yml` 等 YAML 文件，增强版还会自动加载运行目录下的：

```text
config/env.properties
```

这里使用标准 Spring 属性名，不是把整个模型配置塞进 `JAVA_OPTS`。一个完整模型的写法如下：

```properties
chatchat.models.default-chat-model=DeepSeek-V4.1-Flash
chatchat.models.available-chat-models[0]=DeepSeek-V4.1-Flash

chatchat.models.chat-models[DeepSeek-V4.1-Flash].model-name=/models/DeepSeek-V4.1-Flash
chatchat.models.chat-models[DeepSeek-V4.1-Flash].api-key=${CHATCHAT_INTERNAL_MODEL_API_KEY}
chatchat.models.chat-models[DeepSeek-V4.1-Flash].base-url=http://127.0.0.1:31005/v1
chatchat.models.chat-models[DeepSeek-V4.1-Flash].protocol=openai
chatchat.models.chat-models[DeepSeek-V4.1-Flash].timeout=1800000
chatchat.models.chat-models[DeepSeek-V4.1-Flash].max-tokens=-1
chatchat.models.chat-models[DeepSeek-V4.1-Flash].max-retries=1
```

增加第二个模型时继续使用下一个列表下标，并增加对应 Map 配置：

```properties
chatchat.models.available-chat-models[1]=Qwen3-32B
chatchat.models.chat-models[Qwen3-32B].model-name=/models/Qwen3-32B
chatchat.models.chat-models[Qwen3-32B].api-key=${CHATCHAT_INTERNAL_MODEL_API_KEY}
chatchat.models.chat-models[Qwen3-32B].base-url=http://127.0.0.1:31000/v1
chatchat.models.chat-models[Qwen3-32B].protocol=openai
```

模型名、真实 ID 和 URL 都来自配置，代码不会根据名称、厂商或端口推断。真实 ID 不是 `/models/...` 时，原样填写服务实际返回的值。

Agent、普通聊天和数据科学页面现在共用同一套模型连接校验。只有能够解析到非空 `baseUrl` 的模型才会进入前端候选列表；保存 Agent 时会再次校验，并把真实模型 ID 别名规范化为稳定的 `chatModels` 配置键。这样不会再出现“默认模型正常，但 Agent 选中一个缺少 URL 的模型后才在运行阶段失败”的情况，也不会静默改用默认模型。

所有 Spring 配置源先统一映射到 `ModelsConfig`，再由 `ModelResourceRegistry` 对外提供默认模型、可选模型、规范化名称和已校验连接。Agent、控制器及模型工厂只注入注册表，不再分别读取 YAML、环境变量或原始配置 Map。

配置优先级从高到低为：

1. 应用启动命令行参数。
2. JVM `-D` 系统属性。
3. 操作系统环境变量。
4. `config/env.properties`。
5. `application*.yml`。

因此可以在 YAML 中保存默认配置，只在 `env.properties` 中写部署环境需要覆盖的字段。相同属性以高优先级来源为准。

需要使用其他文件位置时，可以设置：

```bash
export CHATCHAT_ENV_PROPERTIES_LOCATION=/etc/chatchat/model-env.properties
```

或者：

```bash
java -Dchatchat.env-properties.location=/etc/chatchat/model-env.properties -jar chatchat-api.jar
```

显式指定的文件不存在时，应用会启动失败并报告具体路径；默认的 `config/env.properties` 不存在时则跳过加载。

完整可复制样例：

- [`env.properties`：JDK/JVM 与七个模型完整配置](examples/chatchat-env.properties.example)
- [systemd/操作系统环境变量文件](examples/chatchat-systemd.env.example)

`env.properties` 中的 `JAVA_HOME`、`JAVA_OPTS` 由发行包启动脚本读取；其余 `chatchat.*` 属性由 Spring 应用读取。同一个文件可以同时包含两类配置。示例中的 `${ENV_NAME:defaultValue}` 表示优先读取操作系统环境变量，未提供时使用冒号后的默认值。

## 9. 安全注入 API Key

不要把真实 Token 提交到 Git 仓库。这里的 systemd 环境文件用于注入密钥，与 Spring 配置覆盖文件 `config/env.properties` 不是同一个文件。创建仅服务账号可读的密钥环境文件：

```bash
sudo install -d -m 750 /etc/chatchat
sudo touch /etc/chatchat/chatchat-api.env
sudo chmod 600 /etc/chatchat/chatchat-api.env
```

文件内容：

```text
CHATCHAT_INTERNAL_MODEL_API_KEY=替换为实际Token
```

systemd 服务中添加：

```ini
[Service]
EnvironmentFile=/etc/chatchat/chatchat-api.env
```

然后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl restart chatchat-api
sudo systemctl status chatchat-api --no-pager
```

如果 Token 已经出现在截图、聊天记录或共享终端中，应按泄露凭据处理并更换。

## 10. Docker 与 Kubernetes 场景

### 10.1 ChatChat 在 Docker、Nginx 在宿主机

容器内的 `127.0.0.1` 指向容器自身，不能访问宿主机 Nginx。需要完成两项修改：

1. 将 Nginx `listen 127.0.0.1:31000` 改为宿主机指定内网 IP，例如 `listen 10.2.25.112:31000`。
2. 将 ChatChat 的地址改成宿主机地址，例如：

```yaml
baseUrl: http://10.2.25.112:31000/v1
```

同时用防火墙严格限制只有 ChatChat 容器网段能够访问代理端口。

### 10.2 ChatChat 与 Nginx 都在 Docker Compose

建议把两者放入同一内部网络，并使用服务名访问：

```yaml
baseUrl: http://llm-nginx:31000/v1
```

此时 Nginx 容器内部应监听 `0.0.0.0:31000`，但不需要把端口发布到宿主机公网。

### 10.3 Kubernetes

建议将 Nginx 作为同 Pod sidecar 或独立 ClusterIP Service 部署：

- Sidecar：ChatChat 使用 `127.0.0.1:31000`。
- 独立 Service：ChatChat 使用 `http://chatchat-llm-proxy:31000/v1`。
- 不要创建公网类型的 LoadBalancer。
- 如启用了 NetworkPolicy，应允许 Nginx Pod 访问 `10.6.65.0/24` 的目标端口。

## 11. 启动与验收顺序

严格按照以下顺序执行：

1. 从应用主机直连所有 `/v1/models`，确认网络和真实模型 ID。
2. 安装 Nginx，写入代理配置。
3. 执行 `nginx -t`。
4. 启动或 reload Nginx。
5. 通过 `127.0.0.1:31xxx/32xxx` 验证 `/v1/models`。
6. 通过 Nginx 验证 `/v1/chat/completions`。
7. 修改 ChatChat 外部 YAML 中的 `baseUrl`。
8. 注入 `CHATCHAT_INTERNAL_MODEL_API_KEY`。
9. 重启 ChatChat。
10. 在前端逐一选择模型并发送最小测试问题。
11. 同时观察 ChatChat 日志和 Nginx access/error log。

验收完成的必要条件：

- 七个前端模型名称均可选择。
- 每个模型请求进入正确 Nginx 端口。
- Nginx 将请求转发到正确上游。
- 不再出现空请求体错误。
- 不出现 API Key 配置错误。
- 模型响应中的模型 ID 与选择模型一致。

## 12. 常见故障排查

| 现象 | 原因 | 处理方式 |
| --- | --- | --- |
| `input: None`、`Field required` | 请求未经过 Nginx，或 Nginx 未缓冲/清除升级头 | 检查应用 `baseUrl`、`proxy_http_version 1.0`、`proxy_request_buffering on` 并 reload |
| `Model API key is not configured` | 配置键绑定失败或环境变量未注入 | 使用 `"[模型显示名]"` 形式的 Spring Map 键；检查 systemd `EnvironmentFile` |
| `Model base URL must not be blank` | 含 `.` 的模型键未被 Spring Map 正确绑定，或选中模型没有对应连接配置 | 将 `chatModels` 键改成 `"[Qwen3.8-27B]"` 形式，并保证默认/可选模型名与方括号内文本一致 |
| `No connection configuration found for selected chat model` | 前端选择值既不匹配配置键，也不匹配 `modelName` | 根据错误中的 `Configured model keys` 修正选择值或新增模型配置 |
| `model does not exist` | `modelName` 与 `/v1/models` 的 ID 不一致 | 原样复制 `data[].id`，包含 `/models/` 前缀 |
| `502 Bad Gateway` | Nginx 无法连接上游，或被 SELinux 阻止 | curl 直连上游；设置 `httpd_can_network_connect`；查 error log |
| `504 Gateway Timeout` | 模型推理超过 Nginx 超时 | 调高 `proxy_read_timeout`，检查模型负载 |
| `413 Request Entity Too Large` | 请求超过 Nginx 限制 | 调高 `client_max_body_size` |
| `java.net.ConnectException` | ChatChat 无法访问 Nginx 地址 | 检查监听地址、容器网络、端口和防火墙 |
| 宿主机成功、容器失败 | 容器内 `127.0.0.1` 地址错误 | 改用宿主机 IP、Compose 服务名或 sidecar |
| 流式输出最后一次性显示 | 响应被代理缓存 | 确认 `proxy_buffering off` |
| 选 Qwen 却进入 DeepSeek | 端口映射或模型 ID 错误 | 对照映射表及各端口 `/v1/models` |

### 12.1 确认应用实际使用 Nginx

请求发生时执行：

```bash
sudo tail -f /var/log/nginx/chatchat-llm-*.access.log
```

如果没有新增日志，说明 ChatChat 没有使用 Nginx 地址，应检查：

- 修改的是否为当前 Profile 实际加载的外部配置。
- 服务是否重启。
- 是否存在环境变量或命令行参数覆盖 YAML。
- 前端当前选择的模型名是否与 `chatModels` 键一致。

### 12.2 检查 Nginx 实际加载配置

```bash
sudo nginx -T | less
```

搜索：

```text
chatchat-llm-proxy.conf
proxy_http_version 1.0
```

## 13. 回滚方案

回滚前先保存原始 ChatChat 配置和 Nginx 配置：

```bash
sudo cp -a /etc/nginx/conf.d/chatchat-llm-proxy.conf \
  /etc/nginx/conf.d/chatchat-llm-proxy.conf.bak
```

需要回滚时：

1. 将 ChatChat 的 `baseUrl` 恢复为原始模型地址。
2. 重启 ChatChat。
3. 停用 Nginx 配置：

```bash
sudo mv /etc/nginx/conf.d/chatchat-llm-proxy.conf \
  /etc/nginx/conf.d/chatchat-llm-proxy.conf.disabled
sudo nginx -t
sudo systemctl reload nginx
```

回滚不会修改模型服务，也不会删除模型数据。

## 14. 生产安全建议

- Nginx 优先监听 `127.0.0.1` 或专用容器网络，不要监听公网地址。
- Token 只通过环境变量或密钥系统注入。
- 不记录 Authorization 请求头和请求正文。
- 模型代理端口不提供公网 DNS，不通过公网负载均衡器暴露。
- 对 Nginx 日志设置保留周期，防止磁盘耗尽。
- 对 `502`、`504`、上游响应时间和模型错误率设置监控告警。
- 对已经出现在截图或聊天记录里的 Token 执行轮换。

## 15. 最终检查清单

- [ ] 所有上游 `/v1/models` 从应用主机可访问。
- [ ] 已核对 `10.6.65.1` 与 `10.6.65.11`。
- [ ] 已核对共享 `10.6.65.11:30002` 的两个模型。
- [ ] Nginx 配置通过 `nginx -t`。
- [ ] Nginx 仅监听预期地址。
- [ ] SELinux 允许 Nginx 访问内网服务。
- [ ] 通过 Nginx 调用聊天接口返回 `200`。
- [ ] ChatChat `baseUrl` 已全部切换到 Nginx。
- [ ] `chatModels` 使用 `"[模型显示名]"` 形式的 Spring Map 键。
- [ ] `modelName` 与各服务 `/v1/models` 返回值完全一致。
- [ ] YAML 中不存在 `[URL](URL)` 或 `&#x20;` 等聊天富文本内容。
- [ ] API Key 未明文提交到仓库。
- [ ] ChatChat 已重启并逐模型验收。
- [ ] Nginx 与 ChatChat 日志中不再出现空请求体错误。
