# Runtime 修复生产发布记录（2026-10-29）

已将应用提交 `3f92445e29d9110f68d51767327d24ed8a5d70a9` 部署到 `192.168.195.221` 的现有运行环境，公开入口为 `http://192.168.195.221:18100/`。代码已推送至 `origin/fix/runtime-analysis-boundaries-20261028`。

本次提交包含此前验收完成的 Runtime 分析执行边界、Cline Python 诊断、SQL Studio 模板发布 P1，以及 Python Studio 授权代码复用整合。没有新建业务数据库、删除数据卷或关闭原检索服务。

## 发布内容与数据保护

| 服务 | 最终镜像 |
| --- | --- |
| API / 已验收前端 | `runtime-os-api:release-3f92445e29d9-20261029` |
| MCP | `runtime-os-mcp:release-3f92445e29d9-20261029` |
| Cline | `runtime-os-coding-agent:release-3f92445e29d9-20261029` |
| 回调代理 | 保持现有镜像，启用独立的 8081 回调白名单 |
| 公开网关 | 保持现有镜像与容器，仅将首页/index.html 路由至新 API 前端；旧静态资源兼容回退保留 |

API/MCP 使用现场缓存的 Java 17 / SQL 工具基础镜像，替换经过隔离验收的同一份 JAR，并核对 SHA-256；Cline 使用缓存的 Node 24 与已验收的 SDK 依赖。镜像约为 API 742 MB、MCP 634 MB，不携带多轮预览 JAR 层。完整镜像 ID、JAR 和 runner 摘要见 [images.json](runtime-reuse-production-20261029-evidence/images.json)。

生产应用原 Compose 项目标签是 `runtime-os-stage-maintenance`，容器名仍为 `runtime-os-app-stage-*`。最终按精确容器身份切换；Compose 仅用于保存和校验现场持久配置，避免误选沿用相同标签的预览容器。

发布前确认无活动 Runtime 任务、Cline 会话或 Python 容器。保留现有 License、密钥、应用 YAML、数据库连接、网络和数据挂载。备份包括生产 API/MCP PostgreSQL dump、关闭写入后的 RocksDB/UI 产物、监控数据、Compose、环境文件、代理配置和容器元数据，均留在受限目录：

`/opt/chatchat-releases/runtime-reuse-release-3f92445e29d9-20261029/private-backup`

SQL 模板发布增量迁移实际作用于 `runtime_os_acceptance_mcp` PostgreSQL，增加列及唯一索引，没有执行 MySQL 脚本或删除原表。切换前后选定原业务对象数量一致：Studio 脚本 1、SQL 模板 90、能力发布记录 91。API/MCP 应用 YAML 摘要和挂载源、目标、实际访问权限保持一致；这些检查不能外推为所有业务表逐行内容均已独立审计。

最终切换没有更换 News、数据库、OpenSearch、Redis、预览容器等无关运行容器。生产网关首页原先来自 `/srv/task-lifecycle-ui` 的旧前端；真实页面检查发现后，将两个首页路由改为 API 的已验收前端，保留旧 `/assets/` 及回退规则，并验证公共首页与新 API 首页字节摘要相同、Cache-Control 为 no-store。详见 [web-entry.json](runtime-reuse-production-20261029-evidence/web-entry.json)。

## 生产实际验证

使用既有配置的内部服务账号，经正常企业登录接口认证；未重置用户、增加权限或直接写入认证记录。账号密钥只在内存解析，短期会话位于服务器受限目录及本地忽略目录，没有进入本仓库。

| 检查 | 实际结果 |
| --- | --- |
| API、MCP HTTP / gRPC | 就绪；API 内 SQL 工具解析验证通过 |
| Cline | 健康，真实 `@cline/* 0.0.93`，build 为应用提交 SHA |
| 网络边界 | Worker 的独立回调监听有效；回调口普通 API 路径返回404，直接访问 Java 通用8080和MCP被阻断 |
| 完整复用 A | COMPLETED，1次真实 Python；main.py / metrics.py 逐字保留，真实报告结果60 |
| 真实修复 C | COMPLETED，3次真实 Python；先记录 KeyError，修复途中又记录 SyntaxError，最终成功返回7；最终与原源码仅相差目标字段 missing→amount |
| 执行绑定 | 最后执行回执的 codeHash 与代码版本 SHA、operation ID 一致；逐次向原 MCP 管理记录核对执行器均已停止，最终 exitCode=0 |
| 公开入口真实浏览器 | 2个任务均通过，0页面脚本错误；执行回执数量、错误、Diff、最终结果及刷新恢复一致，完成后停止转圈 |
| 预览历史恢复 | 原6个已完成场景均重新读取为 COMPLETED，原结果仍可用 |
| 验收结束状态 | Cline 活动会话0，Python容器0 |

生产验证仅使用明确标注的合成源码及常数受控样本，没有访问真实客户交易或资产数据。C 场景保留两次真实错误，未将中途错误当成任务终止，也未隐藏模型修复引入的 SyntaxError。页面展示的是归档执行回执，不推测未归档执行次数。

- [生产执行与源码绑定核对](runtime-reuse-production-20261029-evidence/final-check.json)
- [真实 Cline/MCP/Sandbox 验收](runtime-reuse-production-20261029-evidence/business-smoke.json)
- [网络边界](runtime-reuse-production-20261029-evidence/network-proof.json)
- [浏览器结果](runtime-reuse-production-20261029-evidence/browser-proof.json)
- [生产真实错误及修复 Diff 截图](runtime-reuse-production-20261029-evidence/python-code-reuse-C-diff-visible.png)

## 发布中处理的现场差异

首次 Compose 切换的隔离校验发现：预览 Node/代理沿用了生产 Compose 标签，被 Compose 纳入替换。已触发生产回退，并使用原镜像、环境和数据挂载恢复两个预览容器；没有删除数据卷。恢复后的两容器改用独立预览项目标签，最终生产改为精确容器身份切换，预览已验收历史再次读取成功。原预览 API/MCP 容器未被替换。

随后一次检查因 Docker 将只读挂载的 `Mode` 从空字符串规范化为 `ro` 而回退。确认实际 `RW=false`、源、目标和传播属性一致后，使用有效挂载属性校验，最终发布成功。两次回退没有恢复数据库 dump 覆盖新数据；增量列保留，原服务均恢复就绪。记录见 [早期回退](runtime-reuse-production-20261029-evidence/initial-attempt-rollback.json)、[预览恢复](runtime-reuse-production-20261029-evidence/preview-recovery.json)，当前状态以 [最终 DEPLOYED 记录](runtime-reuse-production-20261029-evidence/deployment.json) 为准。

## 回退方式与验收边界

原生产镜像与停止的原容器均保留，最终旧容器后缀记录于 deployment.json 的 `retiredContainerSuffix`。正式回退须先停止新任务受理、等待或按原接口取消并确认执行器停止；备份当前新增任务/发布记录，再恢复 private-backup 内原 Compose、环境文件、node-proxy.conf、public-gateway.conf，按精确身份恢复保存的原 API/MCP/Cline/代理容器，重新解析其网络身份并恢复原回调端口的防火墙规则，最后验证 HTTP、gRPC、Cline和网关。不能直接运行会选中其他同标签容器的 Compose 替换，也不能仅回退 API 而遗漏 Node/回调代理。

数据库增量列和新任务证据保留，禁止用旧 dump 覆盖当前业务库。若正式运行后已有新的 SQL 作者发布，应先按原 SQL 发布回退迁移审查并下线相关新能力，保留版本、正文和测试证据；不能删除列或只恢复发布记录。将首页切回旧版时，需同时恢复 public-gateway.conf 并检查浏览器实际加载的资源。

以下不作为已完成的生产验收：

- 既有服务账号没有 MCP SQL 模板治理 principal，SQL 发布数据源管理接口按原安全边界返回403（Unknown template principal）。未绕过或自动创建其治理身份。SQL 发布生产业务闭环仍需使用已配置治理权限的真实用户账号验收；此前隔离环境联调记录仍有效，不能冒充本次生产验证。
- 真实客户金融数据、完整跨租户矩阵、所有历史任务与全部新闻/金融检索链路没有在本轮重新执行；无关检索服务未被关闭或替换。
- 通用 Runtime 的业务校验仍为 NOT_EVALUATED/INCONCLUSIVE；本次对合成源码结果60和7做了独立核对，不能外推真实客户业务正确性。

源码与隔离验收的详细说明见 [Python Studio 交付报告](python-studio-code-reuse-delivery-20261029.md)。该报告中“尚未部署生产”描述的是其原隔离验收阶段，当前生产发布状态以本文和本次证据为准。
