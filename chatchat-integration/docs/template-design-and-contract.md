# chatchat-integration 模板设计与契约

本文档定义 `chatchat-integration` 模块在外部系统集成、MCP 网关和调用桥接中的契约。

## 模块职责

`chatchat-integration` 负责连接外部系统、远程 MCP 服务、第三方 API、消息通道和执行桥接。它保持调用链路可靠，不替业务层做模型判断。

## 当前模板设计

- 集成层必须保留 Runtime 下发的工具名称、参数、上下文和 traceId。
- 外部调用结果必须转换为系统统一 observation。
- 网络失败、超时、远端错误和权限拒绝都必须有结构化结果。
- 集成层不得扩大用户授权范围。

## 契约

- 远端成功返回 success observation。
- 远端失败返回 error observation，并保留远端错误码和消息。
- 权限不足返回 permission denied observation。
- 不得把远端失败包装成成功空结果。

## Required Tool Principle

当 required tool 通过集成层执行时，集成层必须给 Runtime 一个明确终态：成功、失败、权限不足或取消。
