# chatchat-enterprise 模板设计与契约

本文档定义 `chatchat-enterprise` 模块在企业治理、权限和审计上的契约。

## 模块职责

`chatchat-enterprise` 承载企业级扩展能力，包括租户策略、权限治理、审计、合规控制和企业资产管理。

## 当前模板设计

- 企业策略可以限制工具、资产、租户、数据源和通知渠道的可用范围。
- 策略结果应以结构化 metadata 或 observation 返回给 Runtime。
- 审计记录必须保留用户选择、Runtime required tools、实际执行结果和失败原因。
- 企业策略不替模型生成业务答案。

## 契约

- 权限策略可以拒绝执行，但必须产出 permission denied observation。
- 不得把权限拒绝包装成“模型认为不需要执行”。
- 任何跳过 required tool 的原因都必须可审计。
- 企业策略新增字段应保持向后兼容。

## Required Tool Principle

用户勾选工具后，企业模块只能允许、拒绝或说明权限不足，不能让流程无声跳过。
