# chatchat-api 模板设计与契约

本文档定义 `chatchat-api` 模块的接口边界、请求传递和响应契约。

## 模块职责

`chatchat-api` 提供外部 HTTP/API 入口、前端静态资源和接口聚合。它负责把用户选择准确传给后端 Runtime。

## 当前模板设计

- API 请求必须保留用户勾选工具、能力、资产范围和查询上下文。
- API 层不应自行裁剪 required tools。
- API 层不应根据自然语言问题硬编码工具选择。
- API 响应应展示 Runtime 的真实终态。

## 契约

- 前端选择项必须进入 `requiredToolNames`、tool scope 或等价 Runtime input。
- Runtime 返回 `FAILED` 时，API 不得改写为 `SUCCESS` 或 `EMPTY`。
- Runtime 返回 `PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED` 时，API 必须透传错误码和失败摘要。
- API 层必须保留 requestId、conversationId、tenantId 等追踪信息。

## Required Tool Principle

用户在界面上的明确选择是一份执行契约。API 层的职责是完整传递，不是重新解释或弱化。
