# 双路径实施与兼容边界

## 系统技能

`runtimeCompilationEnabled=null` 延续原行为；旧 API 没有新增必填项。原来的 `ExternalSkillCompiler`、`ModelDrivenExternalSkillCompiler`、`ExternalSkillProtocolConverter`、需求规划器和执行策略的规则保持原样。新增 gate 在 `DomainSkillService`；明确关闭的技能不能进入 Java 搜索、解析、需求分析和手工编译。旧系统格式校验仍在编译错误恢复边界之前，格式错误仍按旧接口拒绝。

新建页面明确开启编译时沿旧 gateway 编译；编辑旧内容而未切换开关的行为保持原样。编译产生的 source/IR 继续分表存储。已有已发布系统技能不会被批量重编译、自动转换为原生技能，或修改原文。

原有手工协议重编译成功时同步标为 SUCCEEDED，并清空此前编译错误，使恢复后的系统技能能够重新参与原 Java 链路；新增回归断言覆盖旧失败状态的恢复。手工协议校验及编译器本身的规则保持原样。

## 原生 Agent Skills

页面外部导入默认 `skillKind=NATIVE`、系统编译关闭；也可以明确选择 Runtime OS 系统技能。ZIP 与标准目录上传沿用原后台导入队列。目录由浏览器打包为标准 UTF-8、CRC-32 的无压缩 ZIP，逐文件保留原始字节及相对路径，通过同一文件导入入口上传；ZIP 上传保留上传 ZIP 的原字节。一个导入项必须有唯一 `SKILL.md`，多个技能的整个仓库不会猜选或合并。目录没有额外的后端分发接口。

原始包存入现有 `ds_domain_skill_source`，`nativeSourceId` 固定指向该版本；SHA-256、逐文件清单、入口、版本、许可、兼容性和风险是单独元数据。编辑页面原生正文只读；更新内容需导入新的原始包。管理员可通过 `GET /api/v1/data-science/domain-skills/{id}/original` 取回原包并检查 `X-Content-SHA256`。

原生技能明确开启编译时调用同一个旧 compiler，并另存编译输入/产物。无论编译成功、失败或开关切换，原生 `SKILL.md` 与 ZIP 均不变。Cline 继续读取原始正文和资源；Java 路径只接受开启且成功并发布的编译产物。原生编译待发布状态不阻断已经发布的原始包给 Cline 使用。

## 既有 Agent 传递

沿用 `workflowConfig.boundDomainSkillIds`、`ResourceAuthorizationPort`、`SkillAnalysisContextService`、已有 `skill_read` callback 和 task privateState。`skillPurpose=AGENT_NATIVE` 是内部用途标记，不是额外工具授权。原生 name 从原始 metadata 交给 Cline，不拿展示名称替代原始标识。`@cline/core` 当前安装版本实际解析原始 SKILL.md 并核对摘要，解析不重写返回值。

原始文本、脚本与二进制资源逐文件冻结、摘要复核；二进制用 base64 原字节返回，脚本只能读取。旧系统包原来的 scripts 过滤保留。任务继续复核 live authorization；任务已冻结的内容不被新版本静默替换。内置保留名称、重复动态名称的原冲突规则保持，不扫描共享 HOME。

## 明确限制

- 原始上传最大 5MiB；ZIP 最多 500 项、单文件解压 2MiB、总解压 16MiB；拒绝穿越、绝对/歧义路径、重复项、加密、多卷、symlink 和特殊文件。不把 ZIP64 或非标准 ZIP trailer 当成已兼容。
- 为保持既有任务 checkpoint 边界，活动技能正文最多 72,000 字符、资源最多 64 个、每资源 24,000 字节、冻结 JSON 最多 250,000 字符。超限的原包仍完整保存，但标明加载受限；没有截断、删除资源或扩张执行权限。
- Cline 可读取所选技能根目录下完整资源；ZIP 外围的仓库文件仍在原始下载包及摘要清单中，不作为该技能运行资源跨根激活。
- A/B/C 只是静态结构/依赖评估。缺少原地引用或未声明许可会限制加载；危险执行、敏感访问或依赖安装线索也会限制加载。B 级厂商特定字段原样保留，不等于支持其工具、Hook、云服务或网络。
- 静态检测不是恶意代码证明或供应链安全认证。不在导入时执行脚本、安装依赖或激活任何厂商插件。不自动判断所有许可证的法律授权范围。
- 延续已有原始 artifact 的 PostgreSQL 存储，未另建分发服务、技能仓库或 Silo 存储实现；Silo 的其他文件职责保持。

远端实测、回归数量和部署摘要见专项验收记录；历史金融客户验收仍在 `docs/cline-agent-skills/`，不能将其冒充本次原生兼容测试。
