# Agent 数据资源授权复选框布局

问题：Agent 设置弹窗中的资源复选框继承普通输入框的 `width: 100%`、
`height: 38px`，并受到表单 label 的 grid 布局影响，勾选框被放大、文字换行。

修复位于 `chatchat-api/web-app/src/styles/pages/skill-hub.css`：
资源复选框固定为 16 × 16，资源标签使用 flex 同行排列、间距 8px。
仅匹配资源授权内含复选框的标签，资源策略下拉框继续使用原表单布局。

浏览器使用 admin 登录真实服务器，打开“金融文档分析助手”设置。
在 1440、768、390 像素窗口下检查复选框尺寸、文字对齐、页面溢出，
并点击标签两次确认可切换且恢复原勾选状态；关闭页面，不保存 Agent 配置。
预览结果见 preview.json，部署后的结果见 verification.json 和 after-*.png。

发布目录：`/opt/chatchat-releases/agent-resource-layout-20261104-r1`。
镜像：`runtime-os-api:agent-resource-layout-20261104-r1`。
基于上一轮任务证据修复后的 JAR 生成单个 CSS 条目的覆盖包，保留此前修复。
部署记录见 deployment.json，原镜像和 API、node proxy 备份容器均保留。
