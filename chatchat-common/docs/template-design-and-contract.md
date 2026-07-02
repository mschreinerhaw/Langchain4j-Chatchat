# chatchat-common 模板设计与契约

本文档定义 `chatchat-common` 模块在模板、工具执行、证据传递中的公共契约。

## 模块职责

`chatchat-common` 承载跨模块共享的基础类型、枚举、工具输入输出结构、运行上下文和交互 trace。它不决定业务流程，只定义各模块共同认可的数据形状。

## 当前模板设计

- 公共类型必须表达清楚工具名称、参数、执行状态、错误码、错误消息和 metadata。
- 工具输入只代表一次执行请求，不能被当成执行完成的证据。
- 工具输出必须区分 success、error、permission denied、cancelled 等终态。
- `InteractionToolTrace`、observation、runtime event 等证据结构必须能被上层完整追溯。

## 契约

- 公共结构字段应向后兼容，新增字段优先使用可选字段。
- required tool 相关信息跨模块传递时，不得丢失 `toolName`、`required`、`source`、`status`、`errorCode`、`metadata`。
- 任何模块不得把缺失工具输出伪装成成功输出。
- 公共层只提供协议，不硬编码某类业务必须走哪种工具。

## 朴实原则

公共类型要诚实表达事实：执行了就是执行了，失败了就是失败了，没执行就不能假装有结果。
