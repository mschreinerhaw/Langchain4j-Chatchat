# SQL Studio、TPC-DS 与原生 Microsoft Skills 专项

本专项分支：`feature/sql-tpcds-e2e-microsoft-skills`，基线 `33a22cc7cbb23f583d508c648f688d5680f1593c`。

## 已核实的职责与调用链

1. SQL Studio 的 `SqlStudioService` 负责工程、保存版本和模型生成。`SqlStudioDelivery` 明确记录 source-only / SQL 未执行；生成成功不能替代查询验收。
2. `SqlStudioPublicationController` 从当前保存文件获取 SQL 和来源版本，经过 `TemplateCapabilityController.sqlAuthoring` 转发到既有 MCP 控制面。
3. `SqlTemplateAuthoringService` 负责草稿、版本乐观锁、真实试执行、测试指纹、发布与重新执行后的回滚。它复用 `SqlTemplateService`、`SqlQueryExecutionWorkflow`、模板能力目录和既有 release 服务；不得另建发布机制。
4. 发布后的 Agent 绑定、发现和实际执行还需单独验证。列表出现、HTTP 成功及源文件校验都不构成业务通过。
5. Java `BuiltinCodingSkills` 只交付元数据；镜像中 `skills.js` 由任务作用域的 `skill_read` 按需交付。Python Sandbox 执行代码，技能不授予数据、工具、网络或副作用权限。

## 已发现的问题与待核实项

- 原生内置加载器要求顶层 frontmatter.version 与目录版本完全相同，仅接收浅层 `references/*.md`。微软原生包可能使用 metadata.version、单数 reference、scripts 和嵌套资源；当前限制不兼容。应分离包装版本和上游 frontmatter，保留原始字节，按清单读取资源，不执行附带脚本。
- 参数目前采用 `{{name}}`；前端初始推断全部为必填 string。需以实际生成、保存、参数定义和真实数据库执行证明参数未丢失，明确不支持的语义，不能静默替换成手写验收 SQL。
- 已有试执行记录摘要和 SHA，尚需核实真实结果能否沿 MCP → Agent → Python 传递，以及异常能否正确终结。
- 微软仓库不是通用 Pandas 技能集合。必须记录具体 commit、目录、许可证和各文件 SHA；通用数据分析/可视化如无官方资产，明确待补充，保留 Runtime OS 现有技能的真实来源。

## 最小实施与验收顺序

1. 从正式 metadata 接口发现资产「248测试数据库」及 `tpcds` 真实表、字段和时间范围，选取零参数和日期/数字/字符串多参数业务问题。
2. 通过真实模型生成、保存 SQL、试执行、既有发布控制面、授权 Agent 实际调用，逐步记录 ID、版本、参数、结果、耗时和失败原因。
3. 用独立只读 SQL 对账实际结果；覆盖变更重发、禁用、回滚、重复请求、旧版本/过期测试拒绝和越权拒绝。
4. 独立引入少量适用的微软原生包，保证镜像离线分发、元数据发现、完整正文/资源按需读取及 Agent 禁用隔离；不修改现有 Java 技能编译与需求分析。
5. 实际运行 Python 分析与图表，以执行产物及统计量对账验收。失败不可标成功。E01–E17 各项记录真实执行状态，未达项明确保留。

测试数据为 TPC-DS 零售数据，不伪装成客户 070200046604 的金融资产数据。目标库仅作只读查询。控制面验收资产使用独立命名与标识；不改动他人会话、授权和运行任务。

## 最终执行结果

以上为实施前审查与计划。实际部署、65 项相关 Java 检查及额外 publisher 检查、149 项 Node 检查、11 项前端检查，以及真实 SQL→MCP→Agent→Python 数值与产物核验已完成，详情见 `02-implementation-and-checks.md` 与 `03-acceptance-report.md`。参数类型和业务口径仍须发布前审阅；模型超时停止回执、四项基线回归失败、未声明单位和 Azure 依赖未实测等边界均保留，没有以全部通过替代事实。
