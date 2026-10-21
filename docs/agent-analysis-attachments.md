# 对话与 Agent API 分析附件

对话框支持将文件拖入整个输入区域，或从剪贴板直接粘贴文件、图片。文件立即保存到当前用户空间；发送时只选择当前附件栏中的文件作为本次分析依据。移除附件仅取消本次选择，不删除用户空间中的原件。上传失败可以重试。普通文字粘贴、文字拖动和中文输入法行为保持可用。

超过 8,000 个 Unicode 字符的输入在发送时完整保存为 UTF-8 Markdown，保留原始空白和换行，再以附件进入分析。每次最多 8 个文件、原件合计 16 MiB；单张图片最多 10 MiB。支持 PDF、DOCX、XLSX、CSV、Parquet、TXT、MD、JSON、JSONL，以及 PNG、JPEG、WebP、GIF。文件格式受理与内容可解析性不同：损坏文件、缺少解析库和无法确认的图片内容必须在分析结果中说明。

## Agent API

使用管理员签发的专用 Agent API Token。上传接口与提问接口均检查当前角色对已发布 Agent 的使用权限。Token 无法访问管理接口或任意用户空间路径。

```bash
export AGENT_BASE_URL='http://192.168.195.221:18100'
export AGENT_ID='financial_data_analysis'
# AGENT_TOKEN 从安全的环境配置注入

UPLOAD=$(curl --fail --silent --show-error \
  -H "Authorization: Bearer ${AGENT_TOKEN}" \
  -F 'file=@./sales.csv' \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/attachments")
FILE_ID=$(printf '%s' "$UPLOAD" | jq -er '.data.id')

jq -n --arg file "$FILE_ID" \
  '{question:"按币种精确汇总金额，报告行数和数据限制",sessionId:"attachment-demo-001",attachmentIds:[$file]}' > question.json
curl --fail --silent --show-error \
  -H "Authorization: Bearer ${AGENT_TOKEN}" -H 'Content-Type: application/json' \
  --data-binary @question.json \
  "${AGENT_BASE_URL}/api/v1/published-agents/${AGENT_ID}/questions"
```

上传返回 `data.id`、文件名、SHA-256、大小和 `AVAILABLE` 状态。提问新增可选字段 `attachmentIds: string[]`；未提供时沿用原有行为。可以引用同一用户在对话框或用户空间上传的受支持文件。不会自动选择用户的其他文件或历史结论。上传成功后可在其他问题中显式复用，附件不随某次对话完成而自动删除。

提问仍返回 HTTP 202 和原有的 `taskId`、`sessionId`、`statusUrl`、`cancelUrl`、`answerUrl`。按返回地址查询状态和答案，使用 DELETE `cancelUrl` 请求取消。取消后的工作停止状态以现有 Runtime 回执为准。管理界面的 curl 示例增加 `ATTACHMENT_FILE` 可选环境变量和 `attachmentUploadCurl` 字段。

附件 ID 持久化在本次任务请求中；提交前及执行时分别检查租户、用户、状态、类型和总大小。执行时读取 Silo 的确切版本并校验 SHA-256，通过原有 Coding 输入快照归档到任务/运行范围。Cline 按所选 Agent 的角色、模型和 Skills 分析，Python 解码并核验原始文件后计算。图片使用已存在的图片理解服务生成带来源哈希的解释，作为需核对原图的衍生信息传入；解释不冒充原始事实。

带附件的请求进入现有 Cline 文件分析流程；附件本身不授予额外数据库或 MCP 访问权限。报告只能引用已交付的上下文和本次附件，且必须包含附件引用。系统不把结构校验通过等同于业务结论已被独立验证。

## 本次验证（2026-10-21）

- 本地相关 Java 测试 68 项通过，前端相关测试 72 项通过，Vite 与 Maven 打包通过。额外图片测试覆盖原件哈希、当前模型和图片解释的衍生标识。未声称运行全仓库测试。
- 验证服务器部署包 SHA-256：`86a05a99a2888835e379c222c9941460537ea6eea31296c4de702a1edda99210`。测试后端的 1,132 个非静态资源条目保持逐字节一致。
- 真实浏览器在整个输入区域拖入 CSV、粘贴 Markdown，下载回读原文一致；移除当前附件后，用户空间原件仍存在。临时浏览器测试文件已清理。
- 使用真实专用 Agent API Token 上传动态生成的 CSV，提问只传附件 ID。父任务 `e402b629-4eba-3796-8fff-e68d5935402f` 为 `SUCCEEDED`，Cline 子任务 `analysis-coding-849eea783fc2d61f520439da007ded6fb8f0f0ce80de039f7ec79a25549b3a6f` 为 `COMPLETED`。Python 使用 CSV 与 Decimal，得出 `84.79 CNY`，与独立计算一致；13 个归档对象大小和哈希全部一致。
- 匿名上传、跨用户引用、重复附件、不支持格式、未发布 Agent 均被拒绝；Token 无法访问管理与通用用户空间接口。重复提交返回同一任务，已完成任务的取消请求保持终态。临时 API Token 已撤销，临时角色和用户已删除。
- 本次子任务 `executorStopped=true`、`reconciliationRequired=false`；结束时活动 Cline 会话与 Python 任务容器均为 0。本次任务原生业务验证状态仍为 `INCONCLUSIVE`，费用计量 `meteringComplete=false`，不能把测试样例计算一致或费用字段 0 推广为业务认证或免费执行。其他历史任务的既有待对账问题不在本次变更中修复。

证据见 [agent-attachments-20261021-evidence](agent-attachments-20261021-evidence/acceptance.json)。真实 API 验证脚本为 `scripts/verify-agent-attachments-stage.py`，真实浏览器验证脚本为 `chatchat-api/web-app/scripts/verify-chat-drop-live.mjs`。
