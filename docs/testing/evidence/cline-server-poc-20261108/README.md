# 2026-11-08 真实模型 POC 证据

阅读入口：[验收报告](../../cline-server-runtime-tests.md)。evidence-manifest.json 记录导出时 91 个文件的 SHA256；本 README 和 .gitattributes 是导出后增加的索引。文件保留原始字节，避免 Windows 换行转换破坏 Hash。

- `native-source-integrity.json` / `native-host-diff.patch`：608 个 Core/Agents 文件未改，四个宿主补丁。
- `build-and-runtime.json` / `build-logs/`：构建、锁与产物 Hash、运行资源配置。
- `stock/`：未修改官方 Standalone 对照；`editor/`、`patch/`：真实 HTTP 主任务；`search/`：同原生会话搜索；`controls/`：真实拒绝、取消、SSE 断线与回放。
- `editor-r1-adapter-failure/`、`editor-r2-resource-failure/`：保留失败历史，不能当作通过场景。
- `authentication.json`、`final-smoke.json`：平台/MCP 认证读取与最终进程、OOM、API 可用性验证。

events.json.gz、http.json.gz 为 gzip 压缩 JSON。已移除原始 reasoning、重复序列化 agent chunk、snapshot 消息与 metadata；原始 eventId 保留，可能有缺号。工具输入/结果、可观察模型回答、原生错误与 usage 保留，模型与操作者密钥已脱敏。费用 UNKNOWN。

`native-tool-results.json` 按结构化 success/error 重新标明结果；历史 transportEventType 不回写。`complete-project-changes.patch` 包含原生生成的新测试；tracked diff 单独记录于 native-changes.patch。fixture-restoration.json 说明补回原始测试输入，生成代码 Hash 不变。restored-regression 是独立追加验证，不伪装成模型原回合命令。
