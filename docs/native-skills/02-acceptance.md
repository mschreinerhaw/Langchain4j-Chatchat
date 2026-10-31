# 原生 Skills 专项验收

2026-10-31，分支 `feature/native-skills-compile-isolation`。状态：**通过本专项有界验收**。原生包保真、系统编译控制、旧 Java 能力回归及 Agent 隔离分别验证，没有把格式兼容等同于厂商工具执行兼容。

## 实施结果

- 新建、导入、编辑增加系统编译选项；旧数据 null 和旧客户端延续旧行为。原生导入默认关闭编译，系统技能导入默认开启。
- 原生 SKILL.md、references、scripts、assets 按原字节与目录保存；原始包可下载并核对整包及逐文件 SHA-256。编译产物与固定原始 source 分离，修改编译开关不改包。
- 沿原后台导入队列、Agent 绑定、权限检查、skill_read 及任务冻结链路传递。没有新增分发服务、工具权限、Shell、网络、依赖安装或厂商 Hook。
- 提供 A/B/C、来源、版本、许可、风险、编译状态及错误、现有 Agent 绑定数量。目录上传在浏览器生成标准 ZIP，沿用同一文件导入入口。
- 关闭编译的原生技能仍可供 Cline 使用，实际 Java 规划接口返回 `NO_AUTHORIZED_CANDIDATES`；原 compiler/converter/planner 和既有 Runtime adapter 源码与基线一致。

## 自动验证

| 验证 | 结果 |
|---|---|
| Java 定向回归 | 79 项，零失败、零跳过：chat 63、API 14、Skill Runtime 2 |
| 实际数据库持久化 | 3 项：原生导入、编译失败后的原件保留、新建系统草稿失败后的创建时间保留 |
| 旧库迁移 | 旧行新增字段仍为 null，内容不变，迁移重复执行通过 |
| Node | 本地及服务器实际镜像同一套 147 项通过，不重复加总；服务器离线构建/执行 |
| 前端 | 42 项通过，含目录 ZIP 的标准 CRC-32、中文路径、二进制及大小/路径边界；生产构建通过 |
| 发布迁移脚本 | 6 项 Python 标准库测试通过，数据库/Docker 调用使用 mock |

详见 [自动测试](automated-tests.json)、[worker 离线证据](worker-offline-tests.json)。旧 artifact-store 测试构造的是当前 v2 IR，却断言 v1，已修正为核对传入 IR 的实际 schema；未修改或降级 compiler。

## 真实 Cline 与隔离

两次均使用临时 Agent、`deepseek-v4-flash`、授权合成 CSV/Parquet 附件，采用明确的验收提问；没有宣称其他默认模型零提示自动验收。

1. **Agent A**：父任务 `b1deb8c4-a045-3d96-9f60-92a592a957c5`，父状态 SUCCEEDED，Coding COMPLETED，5 次模型调用、2 次 Python 执行。真实读取原生正文及 references/checks.md、scripts/check.py、assets/probe.bin。独立读取 owner-scoped journal，核对 checkpoint/分块完整性、原文含 CRLF 的原始 UTF-8 字节、三资源内容和摘要。二进制返回 `00 FF 0D 0A`，脚本函数名正确；合成数据金额 80.00、缺失 1 行，与独立整数分求和一致。
2. **Agent B**：父任务 `5b4afac6-d10e-3162-90cf-7ff55a9675e1`，同样成功，4 次模型调用、2 次 Python 执行。动态清单为 0、无原生快照；禁用数据分析内置技能，实际只读取仍启用的 Python 开发技能。未获得 Agent A 原生技能，Agent A 配置保持不变。

详见 [原生任务](native-task-live.json)、[Agent 隔离](agent-isolation-live.json)。A 的计量仍有未确认部分，保留原有未知用量预留，未伪造清算或将其清零。

## 真实页面与 API

- 真实浏览器完成三文件目录上传；原始中文路径、CRLF 文本和二进制逐文件摘要匹配；编译默认关闭。
- 新导入原生包开启编译后成功产生独立产物，关闭后手工编译被拒绝；整包下载哈希、固定原始 source 和正文在切换前后不变。
- 页面原生正文只读、清单可见、两个系统默认技能仍可见、指定冗余说明不存在。浏览器 textarea 按 HTML 规则显示为 LF；原始服务器字节另行核验，没有据此修改源文件。
- 零页面错误、零由页面验收触发的任务执行请求。

详见 [导入/编译 API](live-import-control.json)、[浏览器](browser-live.json)。截图和原始任务记录仅保存在忽略的 target 及远端私有验收文件中。

## 最终部署与保护

地址：`http://192.168.195.221:18100`。

- API：`runtime-os-api:native-skills-20261031-r3`，JAR SHA-256 `6892848e1619896b950b5851224382fa70ebe033e2dea52e2591ca95d3bdf452`。
- worker：`runtime-os-coding-agent:agent-skills-native-20261031-r1`，image ID `sha256:7e8e374bbdac6804d90f7997073185fca206d9306454dcd2d265b977bba5ae9c`。
- 两内置技能由当前 Cline parser 实际加载：数据分析 1.3.0、Python 开发 1.0.0；文件 root 所有、运行 UID/GID 1000:32101、只读根目录、非 privileged。
- Docker 运行限制、环境（仅预期 build 元数据变化）、原会话内容及消息索引保持；无活跃 Coding 会话或 Python Sandbox。
- 原有 19 个领域技能的内容指纹一致，原 `ids_data_audit` 配置一致；两个临时 Agent、两个临时原生技能已清理，已完成任务的原始证据保留。
- 原数据库、容器及配置均保留发布备份。最终发布在启动前应用哈希固定的幂等增量迁移，兼容远端 Hibernate validate 模式。

调试中实际修复了启动前迁移顺序、目录 multipart 解析限制及 JPA merge 后受管实体的创建时间问题。上述真实 Agent/浏览器验收执行于 r2；最终 r3 仅补上旧系统技能手工重编译成功时清除失败状态的更新，并通过同一套 79 项 Java 回归后发布。worker 与已验收前端资源保持一致。未将早期失败标记为通过。

详见 [最终部署](deployment-final.json)、[最终运行状态](runtime-final.json)、[清理与 Java 隔离](cleanup-and-java-isolation.json)。兼容边界、大小限制、厂商扩展及静态风险检查的限制见 [实施说明](01-implementation-and-limits.md)。
