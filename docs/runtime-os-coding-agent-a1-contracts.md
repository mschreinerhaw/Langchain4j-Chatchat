# Runtime OS Coding Agent A1 公共契约实现

协议：`runtime_os.coding.v1`。实现位置：[common runtime coding](../chatchat-common/src/main/java/com/chatchat/common/runtime/coding/)。A1 交付具名值类型、纯状态校验、标准错误、JSON Schema 和跨语言哈希测试；任务持久化、租约、预算执行和 Sandbox 接线属于后续任务。

## 1 公共类型与调用语义

| 类型 | 实现内容 |
| --- | --- |
| CodingAgentSPI | submit/get/cancel/resume、带事件/错误/关闭回调的订阅；仅接口，无后台执行实现 |
| CodingTaskContract | Objective、Authorization、Budget、OutputContract 及权限/产物枚举 |
| CodingTaskOutcome | 七状态、ValidationStatus、ExecutionControl、Reproducibility、Usage/Counters、证据引用和完整性 |
| CodingExecutionEvent | 十种标准事件、任务/操作身份、尝试号、持久序号、证据与累计用量 |
| CodingErrorCode 与 CodingContractException | 供应商无关错误；异常持有 CodingAgentSPI.Error，可用于后续 wire 错误映射 |
| CodingCanonicalHash | `coding-canonical-json-v1` 的精确字节序列与 SHA-256 |

SPI 的 submit 返回持久化后快照，不能阻塞等待整个 Coding Loop；本接口实现者负责兑现该承诺。cancel 接受请求不代表执行停止。subscribe 从 afterSequence 后回放再追踪新事件，允许重复投递；Subscription.close 仅关闭监听，不取消任务。Observer.onError 为订阅终止错误；实现不得在该回调后继续投递。游标失效和慢消费者溢出使用标准错误，由调用方读取权威快照并重连。

v1 在首次发布前将审查中的 Consumer 订阅定型为 Observer/Subscription，支持异步错误和关闭回调。公共对象不包含供应商会话及原始工具消息。

## 2 wire 校验顺序

1. 从认证身份确定可信作用域，对报文大小和嵌套深度施加传输层限制。
2. 使用 [coding.schema.json](../chatchat-common/src/main/resources/runtime/coding/coding.schema.json) 执行 draft 2020-12 校验，并启用 date-time format assertions。
3. 映射具名 Java record，由构造器执行语义校验；拒绝未知枚举，不能启用 unknown-enum-to-null、枚举序号或宽松数值/布尔强制转换。
4. Outcome 使用 validateAgainst 绑定原合同；更新使用 validateSuccessor 检查身份、版本、状态与累计用量；授权、CAS、预算及资源解析由后续 Runtime 实现负责。

Schema bundle 入口为 `$defs/task`、`$defs/outcome`、`$defs/event`、`$defs/error`；根 oneOf 用于验证四种报文。各端点必须选择预期的具体入口，不允许以“匹配任意一种报文”代替端点类型约束。Bundle 无外部 Schema 依赖。

Schema 负责结构、必填、枚举、范围和部分状态条件；构造器负责跨字段时间、证据和停止条件；validateSuccessor 负责跨快照约束。这些校验不证明引用已持久化或调用者有权限。引用解析及预算强制执行不在 A1 内。

传输必须先校验再映射：例如缺失 long 会被普通 Jackson 映射成零，带偏移时间会被 Instant 解析器归一化。Schema 拒绝缺失字段、数字字符串、非整数和非 UTC wire 时间，不能依靠映射器自动转换后再检查。

## 3 字段规范

必需集合不得为 null；空集合与空引用语义不同。optional 引用使用 null。引用上限 512 个 Unicode scalar values；scope 身份上限 128，指令 32768，摘要/错误说明 4096；集合最多 256 项且拒绝重复。无效代理项拒绝，非空白判定与 Java Character.isWhitespace 一致。

未知可选字段可忽略，不能因此改变 v1 的执行语义或 hash。版本必须显式为 runtime_os.coding.v1，缺失和未知版本均不默认修复。新增必填或改变字段含义须升主版本。

整数上限为 9007199254740991，确保 Java/Node 无损交换。金额/费用使用最多 64 字符的非负规范十进制字符串：不含指数、前导零、尾随小数零；`0`、`0.01`、`123.45` 合法，`0.00`、`1e3` 不合法。币种使用三位大写代码，具体支持币种由 Runtime 环境校验。

wire 时间使用四位年份 UTC ISO 8601，末尾 Z，小数秒至多三位；Java 时间对象不得含亚毫秒精度。费用上限等为零表示禁止，非无限。预算 DTO 只表达额度，不承诺已实现扣减或强制执行。

OutputContract 的 JSON_LINES 结果格式和独立验证 Schema 固定；requiredFiles 仅接受受限 ASCII 相对路径，不含点路径或反斜杠。该限制不能替代执行器对链接、文件类型及实际路径的检查。Authorization 仅包含具名能力及引用，未列明操作不获授权。

## 4 状态与验证结果

CREATED 可进入 RUNNING、FAILED、CANCELLED、BUDGET_EXCEEDED；RUNNING 可进入五种终态。状态自身允许 revision 增长以反映进展或迟到核对，终态不得回到非终态。validateSuccessor 要求同一租户/用户/run/task、合同 hash、输入引用和创建时间，revision 严格加一，事件序号与更新时间不回退，累计 consumed 用量不下降。reserved 可以因结算而减少，不能拿 consumed + reserved 的重复事件重新累加。

取消标志和已提交的停止意图不可撤销；已接受停止的任务不得转为 COMPLETED。任何终态均要求 executorStopped=true 且无待核对执行；FAILED/CANCELLED 等可以没有 startedAt，表示确认从未启动。停止时间必须位于任务生命周期内。

COMPLETED 要求六项引用齐全，并有实际启动时间和验证记录。`validationStatus=FAILED` 仍可与 COMPLETED 并存，表示任务按目标发现并提交了业务异常。其他终态缺少证据时必须列明 limitations。CANCELLED 与 BUDGET_EXCEEDED 的原因必须匹配已接受的停止原因。

validateAgainst 校验任务身份、inputSnapshotRef、合同 hash、budgetId 和币种绑定；不会将真实超预算用量截成额度值。资源耗尽判定与扣减属于 A2/B3。

## 5 跨语言规范化哈希

算法是受限 Coding DTO 的规范化协议，不是任意 JSON 的 RFC 8785 实现。

1. 使用已校验 v1 合同的已知字段；未知扩展不进入 v1 hash。
2. scope 只保留 tenantId/userId/runId，遵循现有 KernelDataScope 对 ASCII 控制/空格边界的 trim；排除 requestId、conversationId、environment 和 attributes。真正影响执行环境的 environmentRef 仍参与 hash。
3. 对 metadataRefs、acceptanceCriteriaRefs、Authorization 中的集合，以及 OutputContract 的 requiredFiles/acceptedMediaTypes/requiredEvidence 按 UTF-16 字典序排序；deliverables 保留声明顺序。
4. budget.deadlineAt 格式化为 UTC，固定三位毫秒小数；规范十进制字符串保持原值。
5. 所有对象键按 UTF-16 字典序排列；整数使用十进制，无空白；字符串按 JSON.stringify 的转义方式编码，不转义普通 Unicode，不作 NFC/NFD 归一化。
6. 对 UTF-8 字节计算 SHA-256，输出 64 位小写十六进制。

Java 实现与独立 Node 脚本共同对照 [固定测试向量](../chatchat-common/src/test/resources/runtime/coding/canonical-vector.json)。测试覆盖中文、补充平面字符、控制字符、集合/键顺序、追踪变化、未来扩展、毫秒格式、安全整数边界及高精度费用字符串。Node 脚本仅用于协议测试，不是生产 Adapter，也不承担完整 Schema 校验。

## 6 校验命令

```powershell
mvn -pl chatchat-common -am verify
```

跨语言测试需要 Node.js 22+，默认执行 PATH 中的 node；可通过 Maven 参数 `'-Dcoding.node=绝对路径'` 指定可执行文件。测试不会静默跳过 Node 校验。JSON Schema 校验器、Jackson databind 和 JavaTime 模块均只引入 test scope；common 生产依赖仍不包含序列化运行时、Spring 或供应商 SDK。

A1 通过不代表 G0 全部通过：G0 还要求 A2 的执行所有权和预算测试。本轮不启动 A2、B1 或 Cline 集成。
