# 2026-10-23 部署验收记录

功能分支：`feature/mcp-template-centric-permissions`。基线 Tag：`baseline/mcp-template-centric-permissions-20261023`。后端与初版界面提交 `550cb7d11`；License 路径登记修正 `b4adfe974`；内部网关资产权限映射修正 `0cac4a495`；界面修正截至 `79cc42ced`。上述变更均已推送。

部署服务器：`192.168.195.221`。API 页面入口 `http://192.168.195.221:18100`，MCP 运维入口 `http://192.168.195.221:18101/admin/`。

| 服务 | 最终镜像 | 运行 JAR SHA-256 |
| --- | --- | --- |
| API | `runtime-os-api:2.3.2-template-capabilities-20261023` | `4115534f5ccd55c3d0ee6960d9e02fc62da918c08cef2fc0599a3f784bd53855` |
| MCP | `runtime-os-mcp:2.3.3-template-capabilities-20261023` | `9fc7022a04498908812eaefd505b057eab22a0bceb1e87f0f219382734691912` |

应用启动检查通过，运行包与发布清单一致。新镜像使用不含旧业务 JAR 的运行时层，每个应用镜像仅复制一个应用包。保留服务器原有运行配置、密钥挂载、数据库和共享数据目录。最终 MCP 页面修正的所有非管理端静态资源条目均与已通过真实调用验收的后端包内容相同。

## 测试结果

- 19 个后端测试类，共 102 项测试，失败与错误均为 0。覆盖身份签名、模板目录、发布生命周期、固定目标路由、Agent 策略、API 权限、License 覆盖，以及现有 SQL、Python、HTTP、API 工作流。
- 5 个前端测试文件，共 45 项测试通过，覆盖模板选择、发布确认、错误保留、Agent 绑定、权限分类及原发布界面。
- 35 项真实服务验收全部通过。使用临时只读 HTTP 模板，经标准 MCP `initialize`、`tools/list`、`tools/call` 完成实际执行；已认证的 API Runtime 调用也返回 `SUCCESS`。
- 浏览器在最终部署版本检查通过：管理员能读取模板目录，发布确认框可取消，我的发布默认“运行中”，Agent 分配页面可用，按钮悬停为白字蓝底，移动端无横向溢出，无脚本错误。长模板名称可换行。
- MCP 账号操作位于当前用户折叠卡片；“仅有登录审计菜单”的前端权限视图夹具也可渲染页面，不再被空业务菜单状态遮挡。此项为浏览器菜单视图测试，不替代后端授权测试。
- 通过公网关逐项比对 95 个静态资源，内容与最终运行包一致。

真实服务验收包括：

1. 并发重复发布只产生一个逻辑工具和版本；不同发布者使用不同工具身份。
2. 普通用户在未授权时目录为空，不能通过已知模板 Key 直接发布。仅获得模板授权仍不能访问资产；补齐指定资产的网关权限后只出现该模板。
3. 用户不能查看或下线另一发布者的版本；跨租户用户不能发现或调用已知工具。
4. 未绑定 Agent、未知 Agent 和无签名调用被拒绝；发现结果与实际调用权限一致。
5. Agent 解绑、模板授权撤销、资产停用以及工具下线后，后续发现和调用立即被拒绝。
6. 资产配置变更使固定版本失效，旧指纹发布请求被拒绝；重新发布后保留工具名称并递增版本。
7. 业务参数不能替换固定资产。MCP 本地管理员令牌不能调用业务发布控制面；普通发布用户不能访问历史全量管理目录。

生产实际执行使用只读 HTTP 验收模板，没有对现有生产 SQL、SSH 或 Python 业务模板发起额外执行，也未请求模型推理。其他执行类型的结果为定向回归测试通过，不等同于对全部生产连接进行实调。

## 清理及对账

临时用户、角色、租户、Agent、HTTP 资产和运行中的验收发布版本数量均为 0。已发布的验收版本保留为下线的不可变历史及审计记录，未删除历史发布数据。

对账确认现有 MCP 工具权限、用户角色关联和固定模板检索绑定与部署前一致。资源授权仅在首次升级增加 1 条超级管理员的 TEMPLATE 默认授权；其后重启、测试及清理没有改变既有授权。

服务器证据位于：

- `/opt/runtime-os-docker-stage/evidence/template-capability-acceptance-20261023/acceptance.json`
- `/opt/runtime-os-docker-stage/evidence/template-capability-acceptance-20261023/reconciliation.json`
- `/opt/runtime-os-docker-stage/evidence/template-capabilities-2.3.3-template-capabilities-20261023/deployment.json`
- 最终替换前备份：`/opt/runtime-os-docker-stage/evidence/template-capabilities-2.3.3-template-capabilities-20261023/backup-1792728988`

本地浏览器报告与截图位于 `target/template-capability-browser/`，未将生产页面数据、临时会话或凭据提交到 Git。

## 兼容及回滚

旧 Agent 经 API 注册与调用链使用的既有工具名称、固定绑定及执行工作流保留。新业务模板发现和调用要求签名用户/Agent 身份，由 API 内部完成；仅持有 MCP 服务令牌不能获取业务模板清单。

回滚先停止新 TEMPLATE_CAPABILITY 运行版本，再恢复备份中的镜像与部署配置，保留版本、审计及迁移标记。禁止用旧数据库备份覆盖升级后产生的业务记录。部署脚本支持异常时恢复应用，并保留数据库历史。
