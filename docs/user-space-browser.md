# 数据车间用户空间文件浏览器

实施分支：`feature/runtime-os-silo-workspace`。验证日期：2026-10-21。

## 页面与操作

数据车间将“我的数据”和“我的脚本”合并为“用户空间”，入口仍为 `/#/w/data-science/dataScienceData`。旧脚本书签会进入同一浏览器。原页面顶部重复的“我的文件与分析记录”区域已移除；Python 环境、Python 开发和 SQL Studio 保留独立功能入口。

浏览器提供全部文件、用途文件夹、面包屑、列表/网格、名称/状态/用途筛选、排序、加载更多、上传、拖拽上传、预览、下载、删除和多选归类。按钮使用系统主题变量、统一尺寸与 Lucide 图标，适配窄屏。默认用途为原始数据、脚本、参考文档、分析记录、分析产物、运行日志；用户可创建嵌套逻辑文件夹，空文件夹可以删除。系统用途文件夹不能删除。

新文件上传至 Silo。文件夹和文件归类关系持久化于 PostgreSQL，页面刷新后保留；移动文件仅修改归类，不修改对象键、版本、哈希、任务或运行绑定。默认加载 100 个原生文件，可继续分页；旧 Python 文件和脚本来自现有工作台接口。

旧 Python 数据与脚本聚合显示在浏览器中，保留现有下载、编辑、版本和删除链路。本次没有把历史 MCP 文件或 PostgreSQL 脚本源代码迁移为 Silo 原件，不能将页面合并视为历史物理迁移完成。

## 用于开发与分析

支持 PDF、DOCX、XLS/XLSX、CSV、JSON/JSONL、PARQUET、ORC、ZIP、TXT、LOG、Markdown、Python、SQL 和现有图片格式上传，具体大小/配额继续由资产服务控制。ZIP 只作为文件保存，不自动解压或执行。文本预览不超过 2 MiB，图片预览不超过 10 MiB，其他文件可下载查看。

已有 Python 环境就绪时，原始数据预览提供“用于 Python 开发”：服务端读取并校验用户原件，通过现有 MCP 链路生成所属用户的执行副本，返回数据绑定，重复请求复用相同哈希的副本。浏览器隐藏这些副本以免与原件重复显示。删除用户原件先撤销对应执行副本，失败时保留原件。Python 文件可以显式打开为开发草稿副本，不自动运行。

原生资产可多选并通过现有 Cline 分析入口分析，最多 8 个输入和 4 份显式选中的历史上下文，原件合计不超过 16 MiB。历史分析不自动变成事实依据。任务归档文件提供查看和下载，禁止通过此页面删除。已有 OpenSearch 知识接口可用时提供内容检索，原件授权仍由 PostgreSQL 和资产服务核验。

## 接口与授权

- `GET /api/v1/data-science/user-assets?limit=100&offset=0`：所属用户资产分页。
- `GET /api/v1/data-science/user-assets/browser`：所属用户逻辑文件夹及归类。
- `POST .../browser/folders`：`{parentId,name}` 创建文件夹。
- `PUT .../browser/locations`：`{folderId,entries:[{source,sourceId}]}` 批量归类，1–100 个文件，先校验全部文件再写入。
- `DELETE .../browser/folders/{id}`：删除所属用户的空自定义文件夹。
- `POST /api/v1/data-science/user-assets/{id}/python-data`：准备授权原件的 Python 数据副本。

所有租户和用户标识来自认证请求。原生资产和文件夹按稳定用户 ID 归属；旧 Python/MCP 链路使用认证用户名，并通过现有可信别名校验避免与其他稳定用户 ID 冲突。调用方不能提交任意 owner、存储对象键或磁盘路径。文件夹、归类、Python 准备接口仅开放数据车间能力，聊天上传别名不暴露 Python 准备功能。

数据库增量为 `V20261021_03__runtime_user_asset_folders.sql`，新增 `runtime_user_asset_folder` 和 `runtime_user_asset_location`。启动迁移仅调整内置数据车间页面和旧菜单，不新增角色授权；自定义页面和菜单保留。

## 验证记录

本地通过 34 项后端测试、45 项前端测试及 Vite 构建；覆盖文件夹持久化、批量原子校验、跨用户隔离、旧用户名归属兼容、标识碰撞拒绝、MCP 副本字节/去重/撤销、菜单迁移、分页、归类、组件分析输入选择与旧书签。

服务器验证脚本为 `scripts/verify-user-space-stage.py`，使用临时角色、用户与文件验证权限和 Silo→Python 精确字节，结束后清理临时资源。浏览器脚本 `scripts/test-user-space-browser-stage.mjs` 使用已有会话，验证上传预览、移动持久化、视图切换、搜索与旧书签；不调用模型或执行脚本。验证报告与截图见 [本次证据目录](user-space-browser-20261021-evidence/)。服务器登录凭据和令牌不入库。

最终部署：`http://192.168.195.221:18100/#/w/data-science/dataScienceData`，API 镜像 `runtime-os-api:user-assets-f2676174dda3`。20 项真实接口检查与 8 项浏览器检查全部通过，桌面及 760px 窄屏截图已人工查看，临时资源已清理。本次验证覆盖文件浏览、归类和执行数据准备，不重新提交模型分析或运行 Python 代码。
