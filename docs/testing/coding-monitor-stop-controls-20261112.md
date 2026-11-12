# Coding 监控单任务及批量停止

已部署至现有应用和 [18353 监控页面](http://192.168.195.221:18353/monitor/)。刷新页面后，筛选任务、勾选本页任务并点击“停止所选任务”；每行及详情页也提供停止入口。确认框列出任务名称及 ID 尾部，已结束任务不可选。

## 为什么长期显示执行中

改造前当前账户有 60 条非终态记录。取消请求、超时停止请求已经持久化，但部分任务未拿到执行器停止回执，任务日志没有进入终态。原页面只展示 RUNNING，容易被理解为所有任务都仍在运行。

现在列表和详情明确显示“停止待确认”，支持“重试停止”。查询投影增加 `stopPending`、`canStop`，仍以原任务日志为权威；没有直接修改 PostgreSQL 状态或根据任务年龄伪造已取消。最终检查仍有 60 条历史非终态记录，其中 55 条已请求停止：本次没有擅自批量停止或删除这些历史任务，也不声称它们全部已结束。用户可以在页面选择需要处理的任务；缺少停止证明的仍会明确保留待确认状态。

## 执行及权限

* 新增 Java `POST /api/v1/coding-monitor/tasks/{runId}/{taskId}/cancel`。先核对当前账户、租户、Run 和持久化合同，再调用现有 Coding SPI 取消及恢复核对机制；不向前端暴露 Node 管理 API、密钥或 Docker 操作。
* 批量入口使用同一单任务接口，最多两个并发请求，逐项显示结果。部分失败不会吞掉其他任务结果，失败项保留选择；已结束任务幂等返回真实终态，不再派发取消操作。
* 不把 HTTP 202、请求成功或界面点击当成进程停止。未确认时返回 STOPPING；执行器证明真实停止后才收敛到取消/超时终态。
* 修复 Native 收尾顺序：取得并登记停止证明后，历史 telemetry 缺失不再导致取消悬挂；缺失日志照实记录。停止不再因新的模型派发许可检查而被阻止。
* 容量拒绝等分配前场景：只有 Node 明确返回 Session 不存在、Java 没有持久化目标消息下发意图、没有任何已开始操作且已有停止请求时，才使用原有“执行前停止”机制结束。已经下发或结果未知的任务不走此分支，不制造 Worker 回执。
* 列表请求按代次隔离，旧筛选响应不能覆盖新选择；确认前不会发送停止请求。跨账户刷新响应不复用。

## 专用监控代理

现有 18353 Nginx 原先限制监控 API 只能 GET，并以本地 alias 覆盖 Java 的 JS/CSS；仅更新 API 镜像无法给该入口提供有效停止按钮。

已移除旧前端覆盖，让页面和资源来自同一 Java 发布版本，并只额外放行符合任务停止路径的 POST。其他监控查询继续只允许 GET；Java 仍实施最终身份和权限校验。配置经 `nginx -t` 后平滑 reload，Node 没有重启。

可复用模板：[coding-monitor.nginx.conf.template](../../deploy/docker/application/coding-monitor.nginx.conf.template)。渲染 `CODING_MONITOR_PORT` 和 `RUNTIME_API_ORIGIN`，测试配置后加载。现有服务器还保留了原有其他路由，未扩展 Node 管理接口。

## 验证与失败保留

* Java 最终 9 项通过：4 项监控控制授权/幂等测试，5 项 Native 协议与取消测试。覆盖跨租户、跨用户、跨 Run 拒绝，终态无操作，失败仍待确认，异步停止证明、日志缺失及容量拒绝取消。
* [浏览器回归](../../scripts/test-coding-monitor-controls.mjs) 使用真实页面资源和明确的 Mock API，覆盖选择、终态排除、取消确认、部分失败、待确认、筛选响应乱序及无浏览器错误；这不替代真实验证。
* [真实浏览器测试](../../scripts/verify-monitor-batch-stop-live.mjs) 创建两个新测试任务：一个由真实 Cline 生成并启动 Python，另一个因现有租户容量等待。没有调高 Worker 上限，没有人工代写最终脚本。
* 停止前由独立 Docker 查询确认实际 Python PID/PPID/进程组、容器运行状态，且迟到结果不存在。随后在真实页面勾选两个任务并确认批量停止，两项最终均为 CANCELLED。
* 已执行任务使用真实执行器停止证明；等待任务通过执行前取消结束。Java 均无正式结果引用。独立检查确认实际容器不再运行、最终测试未产生 `late-result.json`；刷新与重新部署后仍为已取消。
* 最终 18353 页面再次检查通过；终态重复停止不增加事件，错误 Run 的停止返回 403，未认证停止返回 401，页面没有 JS 错误。

早期失败均保留：初次测试错误地等待两个任务同时执行，触发现有单 Worker 租户容量限制，且错过了第一任务的运行窗口，因此未算作批量停止通过；测试任务已有的迟到文件也照实保留在清理记录中。调整测试为“一项执行、一项等待”后，首次页面自动化因 CSP 禁止测试框架的 `unsafe-eval` 失败，随后按需通过原 API 清理测试任务。最终改用 Locator 轮询，未放宽站点 CSP。专用端口最初仍拒绝 POST 的问题也已通过代理配置修复。没有隐藏这些失败或将接口成功冒充真实进程终止。

证据：[最终真实回归](evidence/monitor-stop-controls-20261112/verification.json)、[停止前进程](evidence/monitor-stop-controls-20261112/processes-before-stop.json)、[Java 终态](evidence/monitor-stop-controls-20261112/outcomes.json)、[最终监控截图](evidence/monitor-stop-controls-20261112/final-monitor.png)、[最终入口校验](evidence/monitor-stop-controls-20261112/final-image-browser.json)、[独立停止与清理检查](evidence/monitor-stop-controls-20261112/final-check.json)。初始用例和 CSP 失败保存在相邻子目录。

## 发布及回滚

最终 API 镜像：`runtime-os-api:monitor-stop-controls-20261112-r3`，本地 image ID `sha256:6a6ea5770370748f367450bbc29c0cffe534b7fbb96a1cc4dff8cf8e2ba2c933`。源码 `267b5b2030558fa3381efc2d01e681ad3825a786`，源码归档 SHA-256 `f8eac30058cc5308fa9303a10f5cc0f6161305e6bd6ce8c1dfe4931e8858fd16`。没有推送或冒称镜像仓库 Digest。

真实批量停止验证在相同后端取消实现的 r2 完成；r3 仅补充前端乱序保护及可核对的确认清单，浏览器回归通过，并在 r3 重新部署后对 18353 正式入口、持久化状态、终态停止及越权拒绝再次验证。MCP、Node、模型、Silo、PostgreSQL 配置均未改动。

保留上一轮已验证 r2 和改造前 API `sha256:a2e76af8350163e85ff85f5734cc38e25970a3ed887f768e25c7c7e32951ac7a`。回滚先排空任务，仅恢复 Compose 的 API 镜像，再对 api 执行 `up -d --no-deps`；如需恢复只读代理，从专项证据目录恢复 `monitor-nginx-before.conf`，经 `nginx -t` 后 reload。勿用整个旧配置覆盖其他服务的后续修改。

服务器记录位于 `/opt/runtime-os/releases/monitor-stop-controls-20261112-r1/`、`r2/`、`r3/`。已归档并删除本次四个已停止的测试 Worker 容器，以及 r1 中间镜像标签；未删除历史任务、证据、持久化卷，也未全局 prune。清理窗口可用空间从 38,192,119,808 到 38,232,625,152 字节（增加 40,505,344），该观测包含并发系统写入，不当作 Docker 层的精确回收量。
