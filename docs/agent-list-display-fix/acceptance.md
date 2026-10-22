# 管理员 Agent 列表显示修复

## 原因与改动

Agent 管理页面的空状态条件原为 `summary.agentCount || 0 === 0`。由于比较运算的优先级高于逻辑或，数量为 21 时条件返回真，页面显示「暂无 Agent 配置」并跳过列表渲染。

将条件修正为 `(summary.agentCount || 0) === 0`，区分「没有任何配置」和「筛选结果为空」。保持原有管理员权限、查询服务、发布状态、分页及资源授权规则。

环境中已核对 `admin` 持有 `platform:agents:all`。后端 `AgentWorkshopController.agentVisibility()` 复用统一权限服务检查这一权限，授予全部维护 Agent 的可见范围。此次未使用用户名判断绕过授权，也未改动角色或业务资源权限数据。

## 自动化验证

- 14 项前端测试通过，其中新增 3 项实际挂载 Vue 页面的回归测试：有数据时显示已发布及未上架卡片、配置为空时显示空状态、筛选结果为空时显示对应提示。
- 新测试加入常规 `test:ui-artifact` 命令，避免仅手工运行。
- 前端 62 个组件结构检查及 Vite 构建通过；API 后端编译打包及 `AgentWorkshopControllerTest` 通过。
- [前端测试报告](evidence/frontend-tests.txt)、[后端测试报告](evidence/backend-tests.txt)、[制品清单](evidence/release-manifest.json)。

## 发布说明

仅更新 API 应用中的前端制品，MCP 服务保持 `2.2.2-rc1`。发布沿用现有 API 部署脚本，备份数据库和部署配置，保留原镜像，验证业务授权指纹与两个服务的实际制品 SHA-256。

旧发布目录中已确认 SHA-256 完全相同的历史 JAR 使用硬链接减少重复存储，原发布路径、备份路径和内容均保留；旧归档包未删除。

## 远程验收结果

2026-10-23 已部署 `runtime-os-api:2.2.3-rc1`，访问 `http://192.168.195.221:18100/#/w/zhice/agents`。

真实浏览器 7 项检查通过：管理员具备全部 Agent 权限、接口同时包含已发布和未上架 Agent、逐页渲染结果与接口全量清单一致、无匹配搜索提示正确、清空搜索恢复列表、三种桌面宽度截图完成、无页面运行错误。

实际核对 **21 个 Agent，已发布 10 个，未上架 11 个**。每页 6 个，共 4 页；遍历结果与接口清单逐项一致。本次只读取列表、分页和搜索，没有修改或新建任何 Agent。

- [1440px 截图](evidence/agents-1440.png)、[1366px](evidence/agents-1366.png)、[1920px](evidence/agents-1920.png)。
- [浏览器检查](evidence/browser-report.json)、[目录数量核验](evidence/catalog-summary.json)、[发布及授权指纹](evidence/deployment.json)。
- 源码修复提交：`bb8a80c22e53f0b6e7b88b52c8056e3e148c0a2a`；发布 API SHA-256：`52ac45019e46bf34a8c5df2f8f0fda64ece4f2d5d204bbbd6b7ad0cff23cf778`。
- 回滚备份目录：`/opt/runtime-os-docker-stage/evidence/publication-ui-2.2.3-rc1/backup-1792711256`。原 API 镜像 `runtime-os-api:2.2.1-rc1` 保留；恢复备份 compose/env 并仅重建 API 服务即可回滚，随后重建 node-proxy、运行既有 worker 限制脚本并重载网关。无需回滚数据库。
- 部署前后角色权限、用户角色、工具授权、资源授权和 MCP 模板绑定指纹一致；既有基线和发布 Tag 保留。
