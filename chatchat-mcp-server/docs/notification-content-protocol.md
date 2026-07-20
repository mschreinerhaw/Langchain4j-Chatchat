# 通知内容发送标准

适用于邮件、企业微信和钉钉通知。协议版本为 `chatchat.notification.v1`。

## 不可变原则

- `sourceContent` 必须是 Agent 已固定生成的最终答案原文；模型不得改写、补充、删减、纠错或重排。
- `sourceSha256` 是 UTF-8 原文的 SHA-256。MCP 服务在发送前重新计算并强制校验。
- `title` 只能逐字提取自 `sourceContent` 的连续片段，不能由模型概括或新写，最长 120 个字符。
- 模型只可输出 `title` 和 `blocks`。`sourceContent` 与 `sourceSha256` 由 API 代码写入，不能采用模型输出。
- `blocks` 必须从第 1 行开始连续覆盖至最后一行，每行恰好出现一次，不能遗漏、重叠或改变顺序。

## 请求结构

```json
{
  "receiver": "接收人",
  "level": "INFO",
  "sourceTaskId": "task-id",
  "contentProtocol": {
    "version": "chatchat.notification.v1",
    "title": "逐字取自原文的标题",
    "sourceContent": "固定答案原文",
    "sourceSha256": "64 位 SHA-256",
    "format": "MARKDOWN",
    "blocks": [
      {"type": "HEADING", "startLine": 1, "endLine": 1},
      {"type": "PARAGRAPH", "startLine": 2, "endLine": 4}
    ]
  }
}
```

`type` 仅允许 `HEADING`、`PARAGRAPH`、`LIST`、`QUOTE`、`CODE`。MCP 服务验证协议后，使用原文行生成最终展示文本，再交给 SMTP 或 Webhook 模板发送。若协议校验失败，本次发送失败，不降级发送可能被修改的内容。

旧调用仍可使用顶层 `title/content`；API 的定时 Agent 通知统一使用 v1 协议，顶层字段仅用于兼容旧版 MCP 服务。

## 短信简报标准

短信不发送完整答案，也不使用 Markdown 排版。API 只逐字提取不超过 36 个字符的原文标题，正文固定为“任务名称、成功/失败状态、登录系统查看详情”的短简报。模型不得概括或改写固定答案，短信中也不复制长正文。
