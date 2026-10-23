# 角色权限：领域技能与 Agent 页签拆分

2026-10-23（北京时间）完成部署和在线验收。

修复分支：`fix/role-resource-agent-tabs`。同步最新远端功能分支 `origin/feature/unified-management-workspaces`（`ecd62726b`），修复代码提交为 `369b347cd`。

原资源目录将 `SKILL` 与 `AGENT_SKILL` 一并分入“领域技能”。现在领域技能保留原分类，Agent 查询服务进入独立的 Agent 页签；历史授权记录、通配规则、类型显示及资源授权页筛选采用相同分类。资源标识和授权写入协议保持原样。

- 定向 Vitest：12 项通过，包含页签切换、历史记录和通配授权分类。
- Vue 结构检查和 Vite 生产构建通过；构建仍有既有大文件体积提示。
- 管理员真实登录和浏览器验收通过，详见 [browser-acceptance.json](browser-acceptance.json)。领域技能 14 项（12 个技能及 2 条通配记录），Agent 22 项（21 个服务及 1 条通配记录）。
- LiveBOS、Python 日志和 YARN 等助手展示在 Agent 页签；两类资源列表、详情、刷新后的页签及资源授权页 Agent 筛选均验证通过。
- 1366、1440、1920 三种桌面宽度无页面横向溢出，浏览器无脚本异常。截图：[领域技能](skills.png)、[Agent](agents.png)。

部署入口：`http://192.168.195.221:18100/#/w/permissions/systemRoles`。

最终 API 镜像：`runtime-os-api:2.2.3-role-agent-tabs-20261023-r2`。

最终 JAR SHA-256：`6059dd077d6d87d26fdbc36f7ccff7a5dade137212bd24361221e78cfd26d4f8`。

最终运行 JAR 哈希匹配，网关逐一获取并比对 88 个前端资源全部一致。服务器完整校验记录：`/opt/runtime-os-docker-stage/evidence/role-agent-tabs-20261023/verification.json`。

部署沿用现有 API JAR，仅替换静态前端资源，逐个校验所有非静态 ZIP 条目一致；使用干净运行时基础镜像重新封装。MCP 保持 `runtime-os-mcp:2.2.2-rc1`，无数据库迁移。部署前后角色权限、用户角色、资源授权、工具权限及 MCP 模板绑定指纹一致。浏览器验收只读取已有授权，未新增或撤销业务规则。

服务器最终部署记录：`/opt/runtime-os-docker-stage/evidence/publication-ui-2.2.3-role-agent-tabs-20261023-r2/deployment.json`。

服务器精确回滚备份：`/opt/runtime-os-docker-stage/evidence/publication-ui-2.2.3-role-agent-tabs-20261023-r2/backup-1792717286`，对应原镜像 `runtime-os-api:2.2.3-rc1`。

部署过程首次候选包因本地构建输出目录与打包目录不一致，缺少前端资源；恢复原镜像后修正路径，新增归档非空、首页和页面资源校验，重新部署 r2。第一次登录检查发生在服务就绪前，返回 502；第一次目录检查发生在名称目录加载结束前，读到占位记录。原始检查记录保留为 [startup-before-ready.json](startup-before-ready.json) 和 [catalog-before-ready.json](catalog-before-ready.json)。补充完整加载等待条件后最终浏览器验收通过。

可复用验收脚本：`scripts/test-role-agent-tabs-browser.mjs`。通过 `CHATCHAT_TEST_PASSWORD` 注入密码，可选配置 `CHATCHAT_STAGE_URL`、`CHATCHAT_TEST_USERNAME` 和 `CHATCHAT_BROWSER_OUTPUT`；凭据不写入验收文件。
