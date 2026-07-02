# chatchat-tools 模板设计与契约

本文档定义 `chatchat-tools` 模块的内置工具设计和执行契约。

## 模块职责

`chatchat-tools` 提供系统内置工具实现、工具 schema、参数校验和结构化执行结果。它执行 Runtime 下发的明确工具调用，不替 Runtime 做流程裁决。

## 当前模板设计

- 每个工具必须声明名称、描述、输入 schema、输出 schema、风险等级和权限要求。
- 工具执行参数来自 Runtime 计划绑定结果。
- 工具不得私自扩大用户选择的资产范围、租户范围或能力范围。
- 工具返回结构化结果，供 Agent reviewer 和 finalizer 使用。

## 契约

- 工具调用成功必须返回可审计的 success observation。
- 工具调用失败必须返回 error observation，并包含错误码、错误消息和必要的 repair hint。
- 权限不足必须返回 permission denied observation，不能吞掉错误。
- 如果工具被标记为 required，失败也是一种终态，但必须有明确 observation。

## Required Tool Principle

工具模块只负责按契约执行。模型认为“不需要执行”不能影响已经由 Runtime 下发的 required tool 调用。
