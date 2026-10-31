# 原生 Skills 专项审查与实施设计

审查基线：`e3e8b1dc3`。2026-10-31。新版任务书在 P2 第 8 项“保证编”处截断；本设计依据其前面完整的目标、边界和 P0–P2 要求实施。

## P0：实际调用链与风险

| 范围 | 实际文件/类 | 现状与调整边界 |
|---|---|---|
| 页面/API | `chatchat-api/web-app/src/views/DomainSkillsView.vue`、`src/js/views/DomainSkillsView.js`；`DomainSkillController` | 新建/编辑、文件/URL 导入；新增可空编译选项，保留旧客户端默认行为，不增加 Agent 分发开关 |
| 后台导入 | `chatchat-chat/.../skills/domain/importing/DomainSkillImportTaskService.java` | 原队列调用 `DomainSkillService.importFile/importUrl`，选项沿同一队列传递 |
| 自动编译 | `DomainSkillService.importBytes → ExternalSkillAdapterGateway.adaptAndCompile → ExternalSkillProtocolConverter + ExternalSkillCompiler` | 当前所有包强制编译，编译失败整个导入回滚。保留 adapter/converter/compiler 的业务规则，在调用边界控制是否编译，原始文件独立先保存 |
| 原始/产物存储 | `DomainSkillArtifactStore`、`DomainSkillSourceArtifactEntity`、`DomainSkillCompilationEntity` | 原始字节现存 PostgreSQL `ds_domain_skill_source.original_artifact`，产物存 `ds_domain_skill_compilation.skill_ir_json`；当前并非 Silo 存储。沿用此实际原始存储，不另建仓库或擅自迁移 Silo 职责 |
| ZIP | `DomainSkillService.markdownFromZip`、`DomainSkillPackageReader.entries` | 原 ZIP 留存，但 Markdown 查找可能合并无入口包，资源路径可能带仓库包装层，读取还跳过 SKILL.md；原生包需独立严格校验，不改旧系统包解释规则 |
| 绑定/授权 | `SkillDefinition.workflowConfig.boundDomainSkillIds`、`DatabaseRuntimeSkillPolicy`、`ResourceAuthorizationPort.explicitlyAllowedIdsForAgent` | 继续使用现有绑定和数据库权限，不把 frontmatter allowed-tools 当作授权 |
| Java 需求分析 | `DomainSkillService.retrievePublished/activateForEvidence`、`DomainSkillPlanningRouter`、`RuntimeOsDomainSkillAdapter` | 关闭系统编译的技能不能进入 Java 需求分析或执行；旧数据 null 仍按旧规则 |
| Cline 传递 | `SkillAnalysisContextService → SkillAnalysisContext.catalog → CodingConfigurationSnapshot → CodingSkillReader` | 原绑定元数据及已有 privateState 冻结机制保留。原生目的显式区分，读取完整原始 SKILL.md，逐文件摘要验证；脚本只作为原始内容读取，不自动执行 |
| Node 发现 | `coding-agent-node/src/skills.js`，`@cline/core.parseSkillConfigFromMarkdown` | 动态技能继续现有 `skill_read`，Cline 自主选择；不扫描共享 HOME，不新增分发服务 |
| 镜像 | `coding-agent-node/Dockerfile` | SDK 0.0.93，Node 24.21；`/srv/coding-agent/skills` 与 `.cline/skills` 指向同一只读、root 所有的默认包，运行 UID 1000。两默认技能已随镜像离线交付 |
| Sandbox/部署 | 现有 `python_execute` 与 `scripts/build-cline-skills-stage.py`、`scripts/deploy-cline-monitor-stage.py`；上一轮应用发布驱动位于忽略目录 `target/financial-presentation-release-r10/deploy.py` | 保持 Runtime 授权、非 root、只读容器、网络及执行限制；不因导入脚本安装依赖或开放 Shell |

## 设计决策

1. `runtimeCompilationEnabled` 使用可空布尔，缺省/null 等价 true；明确的外部原生导入默认 false。编译状态、错误与兼容性元数据独立于原始字节。
2. 原生 ZIP/目录上传逐文件保留字节及路径，必须只有一个明确的 SKILL.md 入口；包含多个技能的仓库应选择具体目录，不猜入口。拒绝路径穿越、重复项、符号链接及解压超限。
3. 标准校验仅评估 A/B/C，不修订原文件。标记厂商专属工具、Hook、危险执行/敏感读取及许可证缺失；不把静态检查称为安全认证。有风险或不兼容包保留原件但限制 Agent 加载。
4. 系统编译依旧使用原 compiler。原生读取只访问原始包，无 Runtime 格式转换；开关变更不能改包。编译失败记录 FAILED，保留原生可用包。关闭开关时旧编译产物不参与 Java 分析。
5. 延续原绑定、授权、任务冻结及冲突处理。原生二进制资源使用 base64 原字节传输；脚本读取不获得执行能力。超过原任务传递预算时明确报错，不截断或静默删除。

## 上游结构/许可核对

核对的是当前官方仓库和规范，不按厂商名称直接宣称兼容：

- [Agent Skills 规范](https://agentskills.io/specification)：SKILL.md + YAML name/description；scripts/references/assets 可选；父目录名称匹配。
- [Anthropic README](https://github.com/anthropics/skills)：技能在 `skills/`，插件清单属于外围；README 明确部分 Apache-2.0、文档技能为 source-available，应检查每包 LICENSE。
- [OpenAI README](https://github.com/openai/skills)：`skills/.system`、`.curated` 等；当前 README 已标记 deprecated 并指向 plugins；每个目录 LICENSE.txt 单独判断，不能继承猜测统一许可。
- [Cline](https://github.com/cline/skills)：技能目录与外围配置分离，根 LICENSE 为 Apache-2.0；仍检查具体包依赖与扩展。
- [Microsoft README](https://github.com/microsoft/skills)：`.github/skills/`、plugins、自定义 agents/MCP 并存，根 LICENSE 为 MIT；Azure SDK 与 MCP/厂商工具依赖不能仅凭 SKILL.md 认定可执行。

不批量复制第三方仓库进生产镜像，不安装厂商插件或执行它们的 hooks。验收用原创最小夹具验证相同标准结构及 A/B/C 行为。

## 回归与验收门槛

旧 API 缺省/旧数据 null、现有系统导入及协议编译、Java 需求分析/规划保持；原生字节哈希和目录资源无损、编译开关切换和失败隔离、A/B/C 与风险限制、现有 Agent 绑定/权限/冻结/版本/同名冲突、两内置技能离线发现，均需实测。测试结果和远端部署事实另行记录，不以设计代替验收。
