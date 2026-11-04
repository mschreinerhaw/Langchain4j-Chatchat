# 历史会话来源失效隔离回归

实测：2026-11-05 06:43～07:01（Asia/Shanghai），环境 `192.168.195.221:18100`，分支 `feature/cline-internet-search`。

## 问题与修复

真实接口确认：最近会话列表正常，三个旧会话详情返回 403，正常的 ECB 图表报告详情返回 200。全局 `KnowledgeResponseAdvice` 将整份会话作为一个来源校验单元，单条回答的来源不可复核或版本变化会阻断整个会话。前端又将详情错误写入侧栏 `historyError`，后续打开正常会话不清除该错误。

只对经过原有所有权检查的 `GET /api/v1/conversations/{id}`、具体 `ApiResponse<Conversation>` 返回类型进行逐消息投影。无法通过现有来源校验的消息仅保留 ID、角色、时间、Agent/模型名，并返回中文占位；任务 ID、正文、引用、轨迹、可视化、UI 内容及记忆上下文均不透出。任一消息受限时，同时移除可能重复携带答案的分析树和非白名单会话字段。响应带 `Cache-Control: no-store`，数据库原始历史不变。

来源详情、任务结果和其他接口仍按原规则拒绝访问。伪造同形 Map 不能进入该投影。前端将详情错误限制在当前会话，并在选择会话时清除旧侧栏错误；实际来源失效时给出中文说明。

## 真实接口结果

| 会话 | 修复前详情 | 修复后详情 | 隔离结果 |
|---|---|---|---|
| `1793799659687-d743c7f92e9be8` | 403 | 200，3 条消息 | 1 条 `SOURCE_UNAVAILABLE` |
| `1793798652777-fb1e0c00abca5` | 403 | 200，2 条消息 | 1 条 `SOURCE_UNAVAILABLE` |
| `1793798631663-a9723ed9604878` | 403 | 200，2 条消息 | 1 条 `SOURCE_CHANGED` |
| `1793817968897-892ecda966d7a` | 200 | 200，3 条消息 | 无受限消息，报告保留 |

从第一个旧会话的实际来源记录抽取 5 个引用 ID，以同一 admin 会话请求来源详情，修复前后均为 403。列表仍为 200、首屏 30 条。回执见 [before-api-proof.json](conversation-source-disclosure-evidence/before-api-proof.json) 和 [after-api-proof.json](conversation-source-disclosure-evidence/after-api-proof.json)。未通过增加授权、取消版本校验、删除历史或替换来源快照消除错误。

## 真实浏览器结果

Chrome + Playwright 使用已有认证会话、应用正常历史加载/选择方法，依次打开三个旧会话，再切换到上述正常报告。没有注入响应或生成新任务。

- 三个旧会话均无侧栏错误或详情加载错误；不可复核回答显示中文占位。
- 正常报告的真实归档图表加载成功，解码尺寸 `1335×700`；该图由此前真实 C11 Python 执行生成，本次只验证历史读取和展示，未宣称重新运行搜索或 Python。
- 英文错误可见元素数为 0，浏览器 `pageerror` 数为 0。图表为懒加载，验证器先滚动至图表再检查解码尺寸。

证据：[浏览器回执](conversation-source-disclosure-evidence/browser-proof.json)、[受限历史截图](conversation-source-disclosure-evidence/restricted-history.png)、[正常历史图表截图](conversation-source-disclosure-evidence/healthy-history-chart.png)。截图只保留聊天视口，不包含其他会话标题。

可复验脚本：[verify-conversation-source-disclosure.mjs](../../scripts/verify-conversation-source-disclosure.mjs)。设置 `REPORT_AUTH_FILE` 为本地私有认证 JSON（含 token/user），`REPORT_RESTRICTED_CONVERSATIONS` 为三个旧会话 ID（逗号分隔），`REPORT_HEALTHY_CONVERSATION` 为正常报告 ID；可设置 `REPORT_OUTPUT` 和 `REPORT_ORIGIN`。脚本不登录或提交模型任务，认证文件不得提交。

## 构建、部署与保留边界

后端 35 项测试通过，包含新隔离测试、现有来源/运行溯源测试、会话 HTTP 集成测试和 owner alias 测试；前端 App 29 项测试通过。真实执行 `npm run build`、完整 Maven package；本地 H2 集成测试的管理员 bootstrap 密码通过测试命令行提供。

部署源提交：`a59a93e883aca5408fe358d8c9484544e4ee9f8b`。

- release：`/opt/chatchat-releases/cline-report-skills-history-r1`。
- 完整 API JAR：428804170 字节，SHA256 `8f9fe336799932a1b12600f406f5c36aac5dec5efdd1318d1149445d317a3750`。
- API 镜像：`runtime-os-api:cline-internet-a59a93e883ac`；仅替换 API 和节点代理，MCP、News、Worker 复用已验证产物。
- 空闲检查通过：无活动 Runtime 任务、Worker 会话或 Python 容器。先备份，再替换；API 端 MCP 目录恢复为 HEALTHY，注册 39 项工具。
- 未执行数据库迁移；部署校验会话内容未改变、消息索引数量未改变、无关容器未改变。保留旧 API/代理和 PostgreSQL 备份。

见[部署回执](conversation-source-disclosure-evidence/deployment.json)。回退使用本 release 的 `deployment.json.retired` 恢复 R12 API/代理；该回退会重新出现旧会话整体 403 的行为，不能视为功能等价修复。

本修复解决会话不可打开及错误提示残留，不恢复已失效来源的历史正文权限。来源暂不可用时可在恢复服务后刷新历史重试；来源版本变化时应重新检索生成报告。原审计内容保留，未将受限历史伪装成可复核报告。
