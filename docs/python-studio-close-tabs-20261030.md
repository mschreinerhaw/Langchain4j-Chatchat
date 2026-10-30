# Python Studio 全部关闭脚本修复与发布

2026-10-30 已将修复提交并推送到 `main`，代码提交 `53a530f56`。

关闭最后一个脚本后显示空白编辑区，不再自动新建 `analysis.py`。空标签状态可持久化，后台刷新和页面重载不会重新打开脚本。保留未保存修改的关闭确认，允许重新打开已有脚本或新建脚本；没有打开脚本时禁用保存、运行和 AI 发送。删除最后一个已打开脚本也复用同一清理逻辑。

## 发布基线

部署前发现生产 API 已运行 `94992565b` 的报告修复，其源代码比当时 `main` 多出九个提交。为保留线上已有功能，在该生产提交上应用本次修复，形成发布提交 `984cb7038`，已推送分支 `deploy/python-studio-close-tabs-20261030`。该提交相对生产基线只有本次四个文件的修改。

从发布提交构建完整前端，使用生产 API 的原始完整 JAR 替换静态资源目录，形成新的可执行 JAR。校验全部 96 个前端资源与构建输出一致；静态资源目录之外的 1,189 个 ZIP 条目逐字节相同，依赖 JAR 保持 STORED 压缩方式。没有替换生产后端实现或降低生产版本。

| 项目 | 值 |
| --- | --- |
| 入口 | `http://192.168.195.221:18100/#/w/data-science/dataScienceDevelop` |
| 发布镜像 | `runtime-os-api:python-studio-close-tabs-984cb7038-20261030` |
| 新 JAR SHA-256 | `9c3b8011afce3905f28ac1542b354d177893d080d933b1c3a1764aab0d05ff19` |
| 原 JAR SHA-256 | `d88fd77b394fe42b6752d106a90e3afb55ff63efa2349a65c8e60b2bef409003` |
| 发布目录 | `/opt/chatchat-releases/python-studio-close-tabs-984cb7038-20261030` |

使用既有 `scripts/deploy-studio-remediation.py` 发布流程，切换 API 和共享其网络命名空间的 Node proxy。保留配置、挂载和现有权限，重新应用既有 Coding 网络边界。MCP、Cline、数据库、网关等其他运行容器 ID 均保持不变。发布前检查没有活跃 Runtime 任务、Coding 会话或 Python Sandbox，并备份 API 数据库及容器配置。发布后全部会话内容指纹和消息索引数量保持一致。

## 验证

- 本次相关单元测试 9 项通过：`npx.cmd vitest run src/js/views/DataScienceView.test.js src/js/utils/pythonStudio.test.js`。
- 原 `main` 及实际发布源码上的专项真实浏览器测试均通过：`node scripts/test-python-studio-close-tabs.mjs`。测试使用隔离接口样本，覆盖关闭非活动标签、关闭最后一个标签、禁用空状态操作、后台刷新、页面重载、创建新脚本、未保存取消／确认及重新打开原脚本。
- 发布源码的 Vue 结构检查、Vite 生产构建通过。另在 `main` 进行的完整 Maven package 通过，但该包未用于覆盖更新的生产后端。
- 原有综合 Python 浏览器脚本在旧的“代码解释”按钮定位处超时；没有把该脚本声明为通过。
- 生产入口实际返回的 `index.html`、DataScienceView JS 和 CSS 的 SHA-256 与发布清单完全一致。
- 通过既有配置的服务账号正常登录，Python workbench 返回成功；真实浏览器未模拟接口，页面 JavaScript 错误为 0。
- API 认证探针返回预期 401，proxy 和 Cline worker 健康。

生产服务账号当前没有资产、脚本或 READY Python 环境，因此线上没有执行标签交互或真实 Python 代码。关闭全部脚本的交互验证来自与发布源码一致的隔离浏览器测试，不将其描述为业务账号线上端到端验收。

## 回退

精确备份及发布回执位于发布目录的 `backup/`、`deployment.json`，不提交其中的密钥、登录态或数据库备份。

保留的原容器：

- `runtime-os-app-stage-api-1-before-studio-1793323574`
- `runtime-os-app-stage-node-proxy-1-before-studio-1793323574`

回退时恢复备份中的 stage 配置，停止新 API／proxy，恢复上述容器的原名称后按 API、proxy 顺序启动，重新应用既有 `restrict-coding-worker.sh` 并重载网关，再检查健康状态。正常代码回退无需恢复整库覆盖新数据。
