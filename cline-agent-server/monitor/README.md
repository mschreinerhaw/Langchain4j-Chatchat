# Native Monitor

独立开发、交互测试与诊断控制台。只使用官方 Standalone 的现有 Session/Events/Permission 接口、Native 持久日志及 Runtime 的任务 Journal、模型回执、Python 回执和文件归档。没有 Agent Loop、SDK Runner、任务数据库或 Docker API。

## 启动

Node >=22，使用私有配置文件：

```sh
CLINE_MONITOR_CONFIG=/private/monitor.json node cline-agent-server/monitor/server.mjs
```

```json
{
  "host": "127.0.0.1",
  "port": 18346,
  "runtimeUrl": "http://127.0.0.1:18340",
  "operator": {"tenantId": "AUTHORIZED_TENANT", "userId": "AUTHORIZED_OPERATOR"},
  "businessState": "/readonly/business/state",
  "models": ["EXISTING_PUBLISHED_AUTHORIZED_MODEL"],
  "native": {
    "url": "http://127.0.0.1:18348",
    "token": "PRIVATE_NATIVE_SERVICE_TOKEN",
    "stateDir": "/readonly/native/state",
    "model": "EXISTING_CONFIGURED_NATIVE_MODEL",
    "workspaces": ["CONTROLLED_WORKSPACE_REFERENCE"]
  }
}
```

配置与凭据保留在服务端私有目录，状态目录只读挂载。`native` 可省略，此时只启用 Runtime 模式。模型名称由服务端白名单及 Runtime 的既有发布/授权配置共同约束。工作区参数是固定引用，不接受浏览器文件系统路径。用 Runtime 账户登录，要求匹配配置的 tenant/user；JWT 和 Native 控制令牌不返回浏览器。浏览器只持有短期 HttpOnly cookie。服务重启后重新登录，执行记录仍来自原有持久记录。

页面：`/monitor/`。开发专项部署地址及实测结果见仓库测试报告。

## 两种模式

- **Native 原生编码**：现有 `/v1/sessions` 创建；`messages` 发送/继续；`permissions/:id/respond` 审批；`cancel` 请求实际停止。复用原生服务的并发限制和恢复限制。Native 本轮完成不表示 Runtime 交付。
- **Runtime 业务集成**：通用任务使用现有 exploration prepare/execute；显式单 CSV 行数/求和合同使用现有 user-assets/analyses 入口，附带 `validationProfile=csv-count-sum-v1`、确切 `valueField`。Runtime 构建任务、预算及不可变数据；官方 Cline 编码和选择工具；Runtime 独立从原始 CSV 字节验算结果。该限定验算不认证其他金融含义、股票行情或任意报告结论。

Runtime 模式不向已有 Coding Session 注入自由消息或浏览器权限决定：当前执行接口不支持这种授权变更，按钮保持禁用。创建另一个明确请求会产生新的 Runtime 任务。Native 模式支持连续会话和真实待批准操作。

## 接口

| 路径 | 行为 |
|---|---|
| `POST /api/login`、`/api/logout` | Runtime 身份交换/清除监控 cookie |
| `GET /api/config` | 非敏感配置引用 |
| `GET/POST /api/sessions` | 原有持久会话投影/调用既有创建入口 |
| `GET /api/sessions/:key` | 原生状态及独立的 Runtime 权威状态、模型回执、执行反馈 |
| `GET /api/sessions/:key/events` | SSE；支持 Last-Event-ID 或 after=nNbB；每条事件有持久游标 |
| `POST /api/sessions/:key/messages` | 仅原生模式；稳定 commandId 幂等消息 |
| `POST /api/sessions/:key/permission` | 仅对应会话当前待决的真实 Permission ID |
| `POST /api/sessions/:key/cancel`、`/reconcile` | 调用既有停止/对账接口；不强制成功、不自动重派 |
| `GET /api/sessions/:key/code`、`/trace` | 实际 Diff/版本或按需读取授权归档 code/stdout/stderr/timing |
| `GET /api/sessions/:key/runtime-events` | Runtime Java Journal 的 sequence 分页 |
| `GET /api/sessions/:key/assets` | 真实归档元数据 |
| `GET /api/assets/:assetId/content` | Runtime owner 鉴权下载，保留原始下载文件名 |
| `GET /api/inputs` | 本人的可选授权文件元数据 |
| `GET /api/dispatches/:requestId` | 有界内存中的 HTTP 传输观察，非任务状态权威；重启后查询持久任务 |

来源事件保留原始时间、eventId、Session/Task/调用身份。SSE 正常背压仅等待当前订阅连接；不终止 Agent。刷新重新补拉持久日志，浏览器按来源 ID 去重。页面关闭不会调用取消。UNKNOWN/不可恢复会话不会从缓存推断为 RUNNING。

不导出 reasoning、控制凭据、原始模型请求体或自动展开源数据。工具输入/输出提供摘要、实际错误及 Hash；受保护源码与日志由用户显式读取。没有完整旧源码时，Diff 明确标记证据不足。Token/计费以 Runtime 回执为准，缺失为 UNKNOWN。

## 验证

```sh
node --test cline-agent-server/test/*.test.mjs
```

真实浏览器脚本使用 `PLAYWRIGHT_MODULE` 指向已安装 playwright-core、`CHROME_PATH` 指向 Chrome，通过环境变量提供用户名/密码，不写入脚本或报告：

```sh
node cline-agent-server/monitor/browser-acceptance.mjs
node cline-agent-server/monitor/browser-runtime-acceptance.mjs
```

参见脚本中的 `NATIVE_MONITOR_*` 环境参数。`EXISTING_SESSION`/`RUNTIME_SESSION` 只观察已有会话；新请求必须保留派发记录，不能以重跑脚本覆盖失败证据。故障源码须在新 Native Session 创建前准备，脚本不提供修复程序、模型响应或成功回执。真实业务、截图和单元夹具测试分别记录。

## 限制与回滚

原生诊断服务当前限制单个活跃 Worker；已有会话须真实取消后才能创建另一个。Native Server 进程重启会将丢失所有权的会话标记 recoverable，不提供伪造的恢复。控制台仅面向固定授权开发操作员，未宣称生产多租户控制台验收通过。

关闭/回滚 Monitor 不影响 Native/Runtime 后台任务。先查询实际运行与停止状态，保留未知操作及持久日志，再停止专用 Monitor 容器并恢复其只读源码/配置。执行链回滚恢复同一备份的 Gateway、API classes、配置与固定 Worker 镜像；不得将同一 Native taskId 交给旧 SDK 重跑。生产默认与旧节点不由本模块切换。
