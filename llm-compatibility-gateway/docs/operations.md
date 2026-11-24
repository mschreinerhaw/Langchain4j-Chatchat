# 架构、运维与现场配置

## 架构

两个 Fastify HTTPS 监听器在同一 Node.js 进程内运行。推理认证使用独立 Bearer Key，可通过 `GATEWAY_CLIENTS` 为每个客户端限定可见的模型 ID 和指定兼容 Profile。管理监听器使用密码登录和带 Secure、HttpOnly 属性的会话。端口默认映射到宿主回环地址，证书和现场网络配置见 [HTTPS 部署](https.md)。

SQLite 的 `user_version` 驱动初始化和迁移。每次发布在事务中写入不可变配置版本，乐观版本校验防止覆盖同时保存的配置。推理请求在开始时持有当时的服务和模型配置。回滚发布一个新版本。加密上游凭据使用 AES-256-GCM。管理 API、备份 JSON 和历史对比展示密钥是否已设置。保留数据库及独立保管的加密密钥即可恢复加密凭据。

推理 API 的目标来源于已发布映射。环境变量 `UPSTREAM_ALLOWED_ORIGINS` 指定逗号分隔的精确 HTTP(S) Origin。建议使用现场内网 IP 或受信任 DNS，并由部署网络控制容器外联。所有服务路径相对于该 Origin，跳转返回错误。上游 HTTPS 使用常规证书验证。填写客户 CA 时，证书必须与所配置上游匹配。Adapter 扩展点位于 `adapters/upstream.ts`，新增协议需要独立语义验证与测试。

## 管理台

1. 在「上游模型服务」新增服务，从允许列表选择 Origin，填写 Chat、Models、认证请求头和各阶段超时。对话路径可指向上游的自定义端点。
2. 点击「检测 / 模型列表」查看真实 HTTP 状态与响应。如果服务未实现模型列表，手动新增模型映射。可为同一个上游 ID 发布多个别名。别名是精确匹配的任意非控制字符 ID。
3. 在「模型映射」录入能力状态。上下文和最大输出字段属于元数据。规则须显式配置，按发布版本执行。
4. 在「兼容规则」编辑表单或 JSON。可预览指定阶段、查看差异并验证后发布。
5. 在「请求实验室」分别选择「直连上游」或「通过网关」保存本页面两次结果用于比较。编辑与已配置服务匹配的路径、附加请求头和请求 JSON。流式事件持续输出，支持取消。实验室结果只保留在当前浏览器页面内，最多显示 500 条记录。业务推理不会留下原始请求正文、模型输出或完整错误体。
6. 在「兼容性检测」运行真实上游探针，展开当次的脱敏证据。历史体检仅保存结论及配置版本。报告导出包含结论。能力状态由管理员根据真实结果维护，诊断建议不会修改生产配置。
7. 在「配置与审计」查看版本、比较、回滚、导入或导出。导入同一服务 ID 和 Origin 时保留本地已有凭据。跨环境导入后在相应服务重新录入凭据。

管理 HTTP API 在 18081，使用登录接口返回的会话 Cookie。其余请求按以下契约提交。

| 方法和路径 | JSON 请求或返回 |
| --- | --- |
| POST `/admin/login` | `{password}`；成功时设置会话 Cookie |
| POST `/admin/logout` | 清除会话 |
| GET / PUT `/admin/config` | 获取当前 `{version,config,allowedOrigins}`；提交 `{version,config}` |
| POST `/admin/validate` | `{version,config}`，验证草稿 |
| GET `/admin/history` | 最近审计版本 |
| GET `/admin/history/{version}` | 指定版本的脱敏配置 |
| GET `/admin/backup` | 下载无密钥配置 JSON |
| POST `/admin/restore` | `{version,config}`，发布导入的配置 |
| POST `/admin/rollback` | `{version,target}`，依据旧版本发布 |
| GET `/admin/overview` | 当前指标、请求元数据和检查摘要 |
| POST `/admin/upstream/models` | `{id}`，返回上游 HTTP 状态和脱敏结果 |
| POST `/admin/preview` | `{alias,body,stage,config?}`，预览规则 |
| POST `/admin/lab` | `{alias?,service?,direct?,path?,profile?,body,headers?}`，返回 NDJSON 轨迹、真实入口连接和协议检查结果 |
| POST `/admin/check` | `{alias}`，流式输出 NDJSON 检查结论及脱敏证据 |

## 规则契约

每条规则使用 `stage`、`op` 和 `path`，按数组顺序执行。同一个模型可以独立配置请求、请求头、普通响应和 SSE Event 规则。在 `choices.0.delta` 这类路径中，数字表示现有数组位置。模型 ID 由映射配置独立处理。

```json
[
  {"stage":"request","op":"rename","path":"max_completion_tokens","to":"max_tokens"},
  {"stage":"request","op":"default","path":"chat_template_kwargs.enable_thinking","value":true},
  {"stage":"request","op":"delete","path":"stream_options"},
  {"stage":"request","op":"clamp","path":"temperature","min":0,"max":2},
  {"stage":"headers","op":"set","path":"x-service-profile","value":"customer-profile"},
  {"stage":"sse","op":"rename","path":"choices.0.delta.thought","to":"choices.0.delta.reasoning_content"}
]
```

还支持 `cast` 的 `type`（string / boolean / number）和 `map` 的 `values` 对象。冲突重命名、无效类型转换和不兼容结构会返回诊断错误。核心请求结构与上游 usage 受保护。SSE 默认要求真实 `[DONE]`。仅当管理员选用补齐策略、上游正常结束且观察到所有 choice 的结束原因时，网关补齐标记。流开始后的错误通过 SSE 错误对象传达，HTTP 状态已经发出。

## 离线部署与升级回滚

在已准备好基础镜像、Debian 包源和 npm 源的构建环境执行 `powershell -ExecutionPolicy Bypass -File scripts/export-image.ps1`。已有实测镜像时加 `-SkipBuild` 导出原镜像。将整个 `release/https-1.1.1` 目录复制到内网主机，再生成该主机自己的 `.env` 和证书配置。Linux 执行 `sh install-offline.sh`，Windows 执行 `powershell -ExecutionPolicy Bypass -File install-offline.ps1`。安装脚本先校验镜像包，再加载镜像并使用 Compose 的 `--no-build --pull never` 启动。

上线前记录镜像 ID、实际端口和项目名，保留旧镜像与配置数据库备份。需完整备份时用 `docker compose stop llm-gateway` 停止写入，再备份数据卷中的 `gateway.sqlite` 及同目录 WAL 文件。升级保持原项目名、数据卷和配置加密密钥，设置 `GATEWAY_IMAGE` 指向新标签，再按离线方式部署。若启动检查失败，设置旧标签并重启。跨数据库迁移回滚时使用与旧镜像匹配的备份。配置问题可直接通过「配置与审计」回滚为新的已发布版本。

运行指标保留最近 5000 条推理元数据，管理页最多展示最近 1000 条。并发限制作用于上游对话，默认每服务 16、整体 32。HTTP 请求体上限 8 MiB。普通上游响应上限 8 MB，单 SSE Event 上限按 200 万 UTF-16 码元检查。网关使用非 root 用户、只读根文件系统、独立持久卷和 Docker 日志轮转。建议现场将管理网络和推理网络按部署网络策略限制访问。
