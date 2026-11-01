# Agent 设置弹窗布局修复

源码提交：4890dceb0；分支：fix/agent-settings-layout。

线上金融文档分析助手设置页存在知识文档和 Skills 重叠、内置技能复选框被拉大、文本与数据库绑定区域错位的问题。[修复前截图](before.png)来自真实线上页面。

原因是 Skills 新增复选框没有使用专用控件布局，继承了普通表单输入框的宽度和高度；同时滚动网格按较小的自动行高分配资源行，高内容在居中排列时溢出到相邻区域。

修复为内容区按 max-content 计算行高、资源内容从顶部排列；内置技能复选框使用 16px 控件和可换行文本布局，并加入系统主题色和分组间距。设置弹窗补充 dialog 语义。未修改 Agent 保存逻辑、权限、技能禁用值或数据绑定。

验证：相关 16 项前端检查通过；72 个 Vue 组件结构检查、前端生产构建和应用打包成功。本地生产构建连接真实服务器 API，1240×824、990×660、390×844 三种窗口均通过几何布局和交互检查。交互检查只修改本地未保存表单，最后取消，不保存 Agent 配置。

复验脚本：chatchat-api/web-app/scripts/test-agent-settings-layout-live.mjs。通过 AGENT_LAYOUT_ORIGIN、AGENT_LAYOUT_AUTH_FILE、AGENT_LAYOUT_OUTPUT 指定环境；可选 AGENT_LAYOUT_API_ORIGIN 将本地生产页面请求转发到实际服务，不提供模拟数据。

包内 HTML、样式和组件与生产构建的 SHA256 对比一致，见 [打包文件摘要](packaged-frontend.json)。[线上部署回执](deployment.json)确认新 API 镜像已部署，原会话内容、消息索引数量和其他容器保持不变；本次未部署 MCP 或 Coding worker。

线上三种窗口宽度复测全部通过，见[几何和交互记录](layout-checks.json)、[桌面截图](agent-settings-1240.png)、[窄窗口截图](agent-settings-990.png)、[手机宽度截图](agent-settings-390.png)。线上 HTML、CSS 和组件文件的实际返回字节与应用包完全一致，见[线上资源核验](served-frontend-checks.json)。

部署镜像：runtime-os-api:agent-settings-layout-20261101-r1。JAR SHA256：8ff56cdbb57e1cc068f04570b5a69ed94cd095d623f904fb05b4278d90e59b3c。服务重启后测试登录会话失效，重新登录后完成全部线上检查；没有将首次 401 的旧会话检查当作布局通过。

[16 项前端检查原始输出](frontend-tests.txt)；前端构建和 Maven package 均成功。
