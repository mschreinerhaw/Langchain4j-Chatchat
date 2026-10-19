# Coding Agent Node

独立部署的 Cline SDK 执行组件，内置 TypeScript 只读监控 API 与浏览器控制台。页面由 Node 自身提供，不依赖 Chatchat 前端或 Java Web 页面。

组件仍通过既有 Runtime 回调协议取得模型、工具权限、预算和执行回执。Node 不自行连接业务数据库、模型供应商或 Docker；对任务执行及结果准入的最终判断由 Runtime 管理。

## 构建与运行

使用 Node 24 LTS 与 npm：

```sh
npm ci
npm run build
npm test
npm start
```

`src/server.js`、`runner.js` 保持已有执行协议；新增监控后端、前端及客户端均使用 TypeScript。编译产物位于 `dist/`；Docker 多阶段构建只保留运行依赖与产物。浏览器验证命令 `npm run test:browser` 使用仓库已有 Playwright / Edge；`CHATCHAT_BROWSER_PATH` 可指定浏览器路径。

环境变量：

| 变量 | 用途 |
| --- | --- |
| `RUNTIME_CODING_HOST` / `RUNTIME_CODING_PORT` | 监听地址，默认 `127.0.0.1:8791`；Docker 默认 `0.0.0.0:3100` |
| `RUNTIME_CODING_CALLBACK_URL` | Runtime 回调 origin，必须为显式允许的内部 HTTP 服务 |
| `RUNTIME_CODING_ALLOWED_CALLBACK_HOSTS` | 回调主机名白名单，逗号分隔 |
| `RUNTIME_CODING_SHARED_SECRET_FILE` | 原有执行协议共享密钥文件 |
| `RUNTIME_CODING_MONITOR_TOKEN_FILE` | 独立只读监控凭据文件，至少 32 字符，禁止与执行密钥相同 |
| `RUNTIME_CODING_MONITOR_TOKEN` | 本地开发可改用环境变量；生产建议文件挂载 |
| `RUNTIME_CODING_MONITOR_DIRECTORY` | 历史目录；不设置时仅保留内存，页面明确显示 |
| `RUNTIME_CODING_MONITOR_SECURE_COOKIE` | HTTPS 入口设为 `true`，浏览器会话添加 Secure |
| `RUNTIME_CODING_CALLBACK_TIMEOUT_MS` | 回调超时，最大 300000ms；仍受 Runtime 任务截止时间约束 |

使用独立 `compose.yaml` 时，先提供文件凭据和回调环境变量，再执行 `docker compose up -d --build`。凭据文件须允许容器 UID 1000 读取；自备 bind 目录须允许 UID 1000 写入。命名卷初始权限由镜像目录设置，整个进程仍以非 root 用户运行。控制台地址为 `/monitor/`。

## 监控内容

- 节点就绪/排空、OS/架构、Node/SDK/构建版本、运行时长、进程内存、CPU 累计计时、会话容量。
- 按状态、任务、Run ID、目标、租户搜索并分页的任务历史；执行阶段、期限、fencing token、恢复模式。
- Python 和 workspace proposal 的真实代码版本、原始字节 SHA-256、操作 ID 与源文件。工作区候选代码不代表已经通过校验或写入用户项目。
- 配置模型与实际返回模型、调用开始/结束、耗时、输入/输出 Tokens、成本。未收到用量显示未取得，不能视为免费或零用量。
- Skills 配置列表、指纹、加载及逐轮应用次数。应用表示注入上下文，不能证明模型遵循了指导。
- 带全局递增游标的事件、断线后保留上次页面、自动恢复刷新；后台页签暂停轮询。

监控在 SDK 事件产生时记录，不依赖 Java 回调成功。持久化失败不会改变执行权限或重放任务；页面显示存储异常。未归档原始数据、模型提示词、私有检查点、原始 stdout/stderr、供应商凭据。

## 历史与重启语义

默认保留 256 个任务、每任务 512 个事件、64 个代码版本元信息，源代码总量 8 MiB、每文件最多 256000 字节。旧任务和源代码逐步淘汰。任务有 tenant/user/run/task/fence 完整身份生成的观察 ID，不会因为不同租户或执行代次同名而覆盖。

历史文件为 `observations.json`，异步串行、同目录原子替换，写入合并间隔 150ms，正常关闭执行 flush。进程突然退出可能丢失最近尚未刷盘的观测，此日志不是执行账本。Node 重启后，之前 RUNNING 的任务标为 `INTERRUPTED`，`reconciliationRequired=true`，未知模型调用标为 UNKNOWN；绝不声称进程或供应商已经停止，也不自动重放。持久化目录中的代码可能包含业务逻辑，按运维数据保护。

## 对其他应用提供的 API

所有数据接口使用独立凭据：`Authorization: Bearer <MONITOR_TOKEN>`。该凭据属于节点运维角色，可查看该节点所有租户的观测，不应下发给终端租户用户。租户级产品需由上层服务鉴权过滤。浏览器使用同源 JSON 登录换取 8 小时 HttpOnly / SameSite=Strict 会话，凭据不放 localStorage、URL 或日志。默认不开放 CORS。`/monitor/` 静态壳可直接打开，运行数据始终需要认证；未配置监控凭据时 API 返回 503。

| GET 路径 | 返回 |
| --- | --- |
| `/v1/monitor/node` | `coding_agent.monitor.v1` 节点快照及保留策略 |
| `/v1/monitor/tasks?status=RUNNING&q=...&offset=0&limit=20` | 任务摘要分页，limit 1–100 |
| `/v1/monitor/tasks/{id}` | 任务详情，不包含源代码正文 |
| `/v1/monitor/tasks/{id}/events?after=0&limit=100` | 游标分页，limit 1–200，`nextCursor` / `hasMore` / `truncated` / `earliestCursor` |
| `/v1/monitor/tasks/{id}/models` | 每次调用的已观测回执 |
| `/v1/monitor/tasks/{id}/skills` | 配置、加载和应用记录 |
| `/v1/monitor/tasks/{id}/codes` | 代码版本元信息 |
| `/v1/monitor/tasks/{id}/codes/{codeId}` | 单个代码版本原文；正文淘汰返回 410 并保留哈希元信息 |
| `/v1/monitor/openapi.json` | 接口发现描述 |

`{id}` 是任务摘要中的观察 ID，并非业务 taskId。不存在或已淘汰的任务返回 404。`POST /monitor/session {"token":"..."}` 登录，`POST /monitor/logout {}` 清理浏览器会话。其余监控写操作返回 405。已有 `/v1/runs`、`/v1/runs/get`、`/v1/runs/cancel` 执行协议继续使用原执行密钥，禁止经公共代理暴露。

提供可复用 TypeScript 客户端与类型：

```ts
import { createMonitorClient } from './dist/monitor-client.js';

// 在调用方服务端执行，token 从其 secret manager 读取。
const node = createMonitorClient({ baseUrl: 'http://coding-agent-node:3100', token });
const health = await node.node();
const tasks = await node.tasks({ status: 'RUNNING', limit: 20 });
if (tasks.items[0]) {
  const events = await node.events(tasks.items[0].id, 0, 100);
  // events.hasMore 时，用 nextCursor 继续；truncated 为 true 表示早期历史已淘汰。
}
```

建议 3–5 秒轮询，设置超时并保留最后成功快照。API 数据属于 `NODE_OBSERVATION_ONLY`，不应作为重试、扣费、停止证明或业务结果准入依据。入口应走反向代理的 `/monitor` 与 `/v1/monitor/` 白名单；HTTPS 时启用 Secure Cookie，执行接口保留在 Runtime 内网。
