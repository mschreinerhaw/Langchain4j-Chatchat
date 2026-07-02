# chatchat-knowledge-base 模板设计与契约

本文档定义 `chatchat-knowledge-base` 模块的知识库检索模板和证据契约。

## 模块职责

`chatchat-knowledge-base` 管理文档、知识资产、检索索引、召回片段和引用证据。它提供知识检索能力，不决定最终业务结论。

## 当前模板设计

- 知识资产是检索范围的一级对象。
- 文档、标签、集合、租户和权限是检索模板的约束条件。
- 检索结果必须携带来源、片段、分数、文档标识和可展示引用信息。
- 当用户显式勾选知识库、文档或资产范围时，该范围必须进入 required tool 或 required retrieval 约束。

## 契约

- 检索成功必须返回可引用 evidence。
- 检索为空必须返回 empty observation，不能伪造证据。
- 检索失败或权限不足必须返回结构化 observation。
- 不得根据自然语言问题硬编码“某类问题一定查某个知识库”。

## Required Tool Principle

用户勾选的知识范围是一份执行契约。Runtime 要么完成检索，要么给出失败、空结果或权限不足的明确 observation。
