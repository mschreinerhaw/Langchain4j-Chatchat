# chatchat-chat 模板设计与契约

本文档定义 `chatchat-chat` 模块的会话、任务事件和 UI 响应契约。

## 模块职责

`chatchat-chat` 负责会话交互、Agent 任务、任务事件、运行状态和 UI 响应。它不执行底层 MCP 工具，但必须忠实呈现 Runtime 状态。

## 当前模板设计

- 用户勾选的工具、能力、资产范围必须传递给 Agent Runtime。
- 任务事件必须完整反映 Runtime 生命周期。
- Runtime 成功、失败、取消、确认等待都必须映射为明确任务状态。
- UI response 只能展示 Runtime 返回的真实状态，不能把失败任务显示为仍在运行。

## 契约

- `RUN_FAILED` 必须映射为任务状态 `FAILED`。
- `RUN_COMPLETED` 必须映射为任务状态 `SUCCESS`。
- `CONFIRMATION_REQUIRED` 必须映射为确认等待状态。
- `PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED` 必须作为失败错误码向前端透传。

## Required Tool Principle

如果 Runtime 返回 mandatory workflow blocked，chat task 必须结束为 `FAILED`，不能继续停留在 `RUNNING`、`WAIT_MODEL` 或 `WAIT_TOOL`。
