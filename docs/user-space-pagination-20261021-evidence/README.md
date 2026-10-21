# 用户空间分页与默认隐藏运行文件

本次验证在 `feature/runtime-os-silo-workspace` 分支进行，2026-10-21。

- `local-validation.json`：32 项后端测试、47 项前端测试及 Vite 构建。
- `package.json`：部署包与经过测试的后端摘要，静态资源之外逐字节一致。
- `deployment.json`：最终镜像及部署备份。
- `api-acceptance.json`：10 项真实 PostgreSQL/Silo 服务分页、筛选、数量及运行证据保留检查，核查 91 份运行文件元数据及抽样原件哈希。
- `browser-acceptance.json`：12 项真实浏览器上传、预览、归类、翻页、每页条数、分页栏可见性、旧书签及窄屏检查。
- `desktop.png`、`compact.png`：包含临时演示数据的页面截图；验证结束后临时资源已清理。

运行文件仅从默认目录与搜索隐藏，没有删除对象或业务证据。验证不调用模型、不执行脚本；凭据和认证会话不入库。
