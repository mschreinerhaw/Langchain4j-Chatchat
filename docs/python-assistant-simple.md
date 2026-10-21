# Python Studio 助手界面精简

2026-10-21，`feature/runtime-os-silo-workspace`。

AI 工程助手已移除顶部工作模式标签与输入框下方的操作下拉框。底部技能入口合并为一个“已发布技能”文字按钮，有选择时显示数量，点击进入现有已发布技能选择弹窗。模型选择仍使用完整一行。

本次调整前端模板和助手网格布局，与 SQL 上下文及逻辑文件夹功能一并打包。现有 Python 视图测试及 Vite 构建通过；浏览器验证模式选择消失、技能入口唯一、技能弹窗可打开、模型选择可见、对话区正常展开及无页面异常。Python 界面验证没有调用模型或执行脚本。

文件夹删除确认也已统一为系统弹窗，保留内容并移到上级。完整实现和联合验收见 [Studio 上下文与逻辑文件夹](studio-context-folders.md)。

入口：[Python Studio](http://192.168.195.221:18100/#/w/data-science/dataScienceDevelop)。部署摘要、浏览器检查与截图见 [验证证据](python-assistant-simple-20261021-evidence/)。
