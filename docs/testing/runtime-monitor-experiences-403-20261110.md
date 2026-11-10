# 运行监控经验接口重复 403 修复

2026-11-10 使用用户提供的服务器和 admin 账号，在 `192.168.195.221:18340` 复现并部署验证。基线为 `feature/runtime-cline-cli-native-20261107` 的 `5e67ae18f`；本次变更针对运行监控，之前尚未完成图文验收的本地修改继续保留。

## 原因与处理

六个监控接口中五个正常，`/api/v1/agent/tasks/runtime/experiences` 返回 403。日志为 `REFERENCE_NOT_OWNED`，不代表登录失效。数据库中的两条旧经验均缺失对应原始任务，其中一条包含无法按当前账号复核的证据引用。全局来源检查拒绝整个经验汇总，页面的 `Promise.all` 又把单个失败传播到整个看板，使实际存在的 15 条任务显示为空；每五秒轮询重复请求失败接口。

后端为具体的经验汇总 DTO 增加逐条投影：从持久化经验和原始任务校验来源及 producer run，即使摘要已截断、没有引用，也不直接放行。受限行只保留经验/任务/Agent ID、更新时间和固定提示，未知的新内容字段不会自动公开。关联索引和场景文本来自全租户经验，必须复核当前页以外的来源；来源无法核验或超过有界检查预算时暂不展示，原总数保留。该响应设置 `Cache-Control: no-store`。数据库历史不改写，原始来源和任务详情的访问规则保持生效。

前端分别处理六个区块的加载结果，经验失败不再清空可读任务与计数。401/403 后暂停对应区块的自动重试，手动刷新或切换租户可重新加载；临时服务错误继续允许恢复。失败区块的旧内容被清空，受限经验不会伪显示为零分或否定反馈。

## 验证

| 检查 | 结果 |
| --- | --- |
| 前端 TasksView、加载恢复、API 登录状态、监控展示回归 | 52/52 通过 |
| 后端经验投影、持久化来源绑定、任务/会话披露回归 | 28/28 通过；相关 17 模块 Reactor 编译成功 |
| 前端构建及 Vue 结构检查 | 通过 |
| 真实浏览器，无 mock | 15 条任务可见，经验页可用，七次实际经验请求均为 200；手动刷新及页面重载通过；0 API 失败、0 JS 异常、0 控制 POST |
| 访问边界 | 未登录仍拒绝；伪造 tenantId 不改变认证租户；旧证据的直接读取仍为 403 |
| 部署文件与服务 | 114 个文件逐项 SHA-256 一致；API/Gateway/MCP 运行，两个监控 healthy |

这些是本次修复的相关回归，不是全量 Maven verify。额外尝试的既有 `AgentRuntimeMonitoringTenantIntegrationTest` 和 `AgentTaskControllerTest` 未通过，涉及现有 Spring 测试的管理员初始化、语义策略 fixture 和菜单权限种子；探索性 fixture 改动已撤回，不能声称全量 Spring 集成验收通过。日志保存在本地忽略目录 `target/continuation-20261109/monitor-403-*.log`；最终后端回归日志为 `target/monitor-403-20261110/backend-regression.log`。

## 部署与回滚

2026-11-10 10:05:33（Asia/Shanghai）完成专项 API 增量部署：三个 Java class 和完整前端构建资源。部署前确认没有活动/等待任务及 Native STARTING/RUNNING Worker，只重启 `cline-business-api`。未变更数据库、角色、模型或任务预算。

- 页面：`http://192.168.195.221:18340/index.html#/w/zhice/tasks`。
- 备份：`/opt/cline-native-business-20261108/backups/monitor-403-20261110T020457Z/`。
- 现场证据：`/opt/cline-native-stability-20261110/monitor-403/`。
- [部署文件清单与原文件 Hash](evidence/runtime-monitor-403-20261110/deployment.json)
- [修复前接口状态](evidence/runtime-monitor-403-20261110/before.json)
- [真实浏览器结果](evidence/runtime-monitor-403-20261110/browser.json)
- [部署后 Hash、健康状态及原始证据访问检查](evidence/runtime-monitor-403-20261110/final-check.json)

回滚先确认没有活动任务，再停止专项 API。按照备份的 `previous-manifest.json` 逐文件恢复：原值为 Hash 的文件从备份恢复，原值为 null 的仅移除本轮新增对应文件；核对路径位于专项 `classes` 内。原有带 Hash 前端资源保留兼容缓存页面。启动 API 后重新检查登录和任务读取。回滚不会修复旧版经验接口的 403。

公开证据不包含凭据、原始来源内容、完整模型事件或浏览器私有截图。
