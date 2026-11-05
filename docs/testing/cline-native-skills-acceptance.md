# 原生 Skills 集成验收

本轮直接使用已安装的Cline0.0.93公开发现器、配置服务、Skills执行器和原生文件工具，不以Parser调用作为验收。当前工作分支 `refactor/cline-native-skills-20261106`，继承前次整改源码及证据。API能力和兼容限制见[调查](../audits/cline-native-skills-api-review.md)，删除与回滚见[迁移记录](../audits/cline-native-skills-migration.md)。

## 自动化覆盖

| 用户要求 | 验证方式与边界 |
|---|---|
| 简单CSV可直接编码 | 同一真实SDK Agent，原生工具可见但不读取Skill，直接提交代码并确认实际执行调用；另需真实Provider任务记录 |
| Parquet适用Skill | 原生工具加载python-data-analysis、原生read_files读取data-methods；真实Provider选择单独记录 |
| 图文报告Skill | 原生调用runtime-report-delivery/plotting，原有报告及图形合同保持；真实图文任务单独核验 |
| 多Skill协作 | 原生会话可按需读取多个包；无固定Skill数量限制或额外执行循环 |
| 无关Skill不加载 | 发现15个包不等于正文进入模型；断言未选择正文不在初始消息和观测中 |
| 动态隔离 | 两租户/任务私有目录，拒绝跨任务与宿主路径；活授权撤销阻断缓存正文及参考读取 |
| 内容一致 | 全部镜像Skill/资源逐字比对，Microsoft来源hash及许可保持；自有元数据迁移正文hash不变 |
| 压缩后继续使用 | 实际安装SDK执行压缩，早期正文被移除后经官方工具再次完整加载 |
| Node重启 | 两个真实Node进程独立启动并发现同样的任务目录与Skill身份 |
| Python修复/报告不退化 | 原有SDK Editor/Patch/检查点、STOP、预算与报告回归，候选真实Docker及Provider任务另列 |

本地原生专题测试已验证上述核心组件；完整Node回归、Java真实SDK Schema桥接、浏览器与远端候选门禁以最终日志为准。Java初轮83项通过，13项SQLGlot工作区用例因未配置本机Python而跳过，不计为通过；新增原生工具Schema桥接单列。尚未完成的发布和真实模型结果将在同一记录补齐，不冒称全项已完成。

## 原件与计数

Skills分发阶段取得磁盘文件与模型实际读取分列。`SKILLS_DISCOVERED`记录原生组件名称和发现数量；`SKILLS_LOADED`只在原生调用返回已授权正文/参考后出现。普通Monitor不发布正文。原生工具名称为`skills`、`read_files`，没有模型可见的`skill_read`。

[SDK版本与API证据](evidence/cline-native-skills-20261106/sdk-api-audit.json)由可重复脚本导出。真实Provider任务必须保留问题、模型、预算、资产hash、原生工具调用与Python结果，并独立核对报告质量；受控SDK测试不能冒充真实模型自主选择，也不能以执行COMPLETED替代报告正确。
