# Java 前置 Skills 关闭与 Cline 按需读取

本次范围仅调整 Skills 所有权。Java 需求分类、详细规划、附件预解析、数据准备、任务合同、权限、预算、取消、恢复和结果验收均保留原路径。未启用绕过分类器、规划器或附件解析的新模式。

原链路：入口 → Java SkillRouter 发现/部分兼容读取 → Java 原有分类/规划/数据准备 → 任务委派 → Native 分配时加载全部 Agent Skill 包 → Cline。

调整后：入口只复制已配置 Skill ID 引用 → Java 原有分类/规划/数据准备 → 原合同和调度链路 → Native 只接收引用说明 → Cline 视任务需要读取 Skill → Java 原结果验收与交付。

部署设置 `CHATCHAT_RUNTIME_CONTROL_PLANE_JAVA_SKILLS_ENABLED=false`。该开关关闭 Java 前置 SkillRouter、兼容 DomainSkill 读取/证据激活及 Skills 方法规划。默认 true 保留回滚能力。恢复 true 后新任务使用旧路径；已提交任务继续遵守原冻结合同。

配置 Skill ID 引用不加载正文、不作语义匹配、不运行方法规划。Cline 可通过 `runtime__agent_skill_read` 自主读取相关 Skill；Java 回调只承担实时权限校验与内容传输，首次主动读取后冻结版本供恢复重读，不能扩大数据或工具权限。Native 镜像内置 Skills 继续由 Cline 使用。`DEFERRED_TO_CLINE` 的 Java selected/activated/loaded 计数均为 0。

服务器部署必须从源码编译。`deploy/docker/source-stack/Dockerfile.java-skills` 编译前端和 Java 模块，并在服务器构建内执行相关回归测试；最终镜像只复制该构建生成的应用包。不得上传本地应用 JAR、替换 class 或差分修补 JAR 作为本次交付。

验证重点是 Java Skills 调用/加载归零、Cline 按需读取可用，以及原分类、规划、附件解析、权限和执行恢复测试继续通过。Java 模型调用次数不要求归零，也不能把跳过既有分析流程视为优化收益。
