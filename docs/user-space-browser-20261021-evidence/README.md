# 用户空间浏览器验证证据

- `local-validation.json`：34 项后端测试、45 项前端测试、生产前端构建。
- `package.json`：部署包 SHA-256；1146 个非静态后端条目与通过测试的后端包逐字节一致。
- `deployment.json`：最终部署镜像、备份目录、Silo 固定版本和数据库增量摘要。
- `api-acceptance.json`：20 项真实接口校验；临时身份、文件、副本与文件夹已清理。
- `browser-acceptance.json`：8 项真实浏览器检查，无页面异常；临时文件和文件夹已清理。
- `desktop.png`、`compact.png`：1680px 桌面和 760px 窄屏验证截图，含清理前的临时演示文件夹。

验证未调用模型或执行脚本。认证会话和服务器凭据不包含在证据中。
