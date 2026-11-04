# 按启用状态逐步展示资料配置

交互顺序：勾选“从知识文档中查找答案” → 选择知识文档 → 选择查阅策略。
未勾选任何资料时，文档选择和策略均不渲染。启用知识文档后，两项按顺序展示在
勾选框下方同一缩进配置区内。若只启用其他资料类型，只显示通用策略，不显示文档选择。
取消勾选收起配置，重新勾选保留当前选择；保存时仍使用既有 enabled 字段控制资源使用。
手动策略明确说明在对话中勾选使用资料后查阅。

源文件：AgentWorkshopView.vue、skill-hub.css。
新增 AgentWorkshopResourceFlow.test.js 覆盖条件展示、顺序、选择保留、
已有启用配置回显和仅启用其他类型的情况，并加入常规 UI 测试命令。
相关 15 项测试和 75 个 Vue 组件结构检查通过。

真实 admin 登录的浏览器验收，在 1440、768、390 像素窗口下反复切换启用状态，
从真实文档选择器选择文档，再切换策略，检查收起及重新展开后的数据保留。
验收不保存 Agent 设置。结果见 verification.json，截图分别记录未勾选和已勾选状态。

发布目录：`/opt/chatchat-releases/agent-settings-flow-20261104-r1`。
准备脚本：`scripts/prepare-agent-settings-flow.py`，基于上一轮的精确运行 JAR，
只覆盖 Agent 设置组件、入口引用和样式，保留之前的修复。部署报告见 deployment.json。
