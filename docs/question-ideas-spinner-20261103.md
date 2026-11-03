# 灵感请求状态与转圈提示

数据探索和智策空间复用 PromptComposer，两个入口同步显示相同请求状态：

- 待响应：按钮显示旋转图标和“思考中”，建议面板显示旋转图标、“模型思考中”和等待提示；设置 aria-busy=true、data-request-state=pending。
- 成功：停止旋转、按钮恢复“灵感”，显示已生成建议数量；data-request-state=succeeded。
- 失败或超时：停止旋转、显示实际错误和重试入口；data-request-state=failed。
- 关闭、修改上下文和组件销毁继续沿用原有取消机制，避免展示过期结果。选择建议仍只填充输入框。

动画在用户启用减少动态效果时停止，文字状态仍保留。请求等待文案不再承诺通常 10–30 秒，实际模型时间可能更长。

验证：PromptComposer（普通及紧凑布局）、数据探索、智策空间相关 84 项测试通过，生产构建通过。浏览器验证检查真实后端请求、实际 CSS 旋转动画、成功后停止旋转及建议可编辑；发布只替换前端资源，逐条确认所有后端类与依赖保持一致。

部署镜像：`runtime-os-api:question-ideas-spinner-20261103-r1`。工具目录 HEALTHY，39 个工具；无数据库迁移，保留 SQL 元数据重构及原有灵感请求修复。[部署证据](question-ideas-spinner-20261103-evidence/deployment.json)

本地验证中观察到一次模型请求返回 HTTP 504；没有将其当作成功生成。超时和失败的停止加载及重试由组件测试覆盖。

最终单独验证两个页面均通过：智策空间真实请求 16,972ms，数据探索 36,407ms；每次点击恰好发送一次后端请求，返回三条建议，请求期间两个转圈图标实际播放动画，成功后旋转图标消失，选择建议没有自动发起分析。[智策验证](question-ideas-spinner-20261103-evidence/zhice-browser.json)、[数据探索验证](question-ideas-spinner-20261103-evidence/exploration-browser.json)、[思考状态截图](question-ideas-spinner-20261103-evidence/exploration-pending.png)。
