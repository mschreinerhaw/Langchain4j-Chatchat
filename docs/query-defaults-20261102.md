# SQL 参数已填写却被拦截

2026-11-02，关系库查询的“执行查询”误用了完整模板保存校验，旧分类不在当前分类列表中的 SQL Studio 模板会被判定为缺少“能力分类”，即使 SQL 入参和默认值已提供也不会发出测试请求。

现在执行测试只校验数据源、SQL/查询步骤及实际查询入参。模板显示名称、说明、分类等发布信息继续在保存时校验。列表中的“测试”入口使用相同的查询校验范围。默认值初始化与类型转换保持原有 schema 语义，包含数字 0、布尔 false 及允许的 null。

源码改动位于 `chatchat-mcp-server/web-app-mcp/src/scripts/components/CrudCatalog.js`。`scripts/test-simple-query-editor.mjs` 覆盖查询校验范围、保存必填约束、0/false/null 默认值以及真正缺失必填入参的情况。

浏览器复现确认修复前的拦截发生在前端：输入 warehouse_id、起止日期并保留默认 min_on_hand=0、category=null，收到“能力分类不能为空”，无测试请求发出。修复后同一库存快照查询返回 5 行，HTTP 请求中的类型和值与输入一致。保存时清空分类仍被拦截，列表测试参数弹窗正常打开。

部署镜像 `runtime-os-mcp:query-defaults-20261102-r1`，发布备份目录 `/opt/chatchat-releases/query-defaults-20261102-r1`。仅替换管理页静态资源，保留此前内置金融数据源修复。验证脚本 `scripts/verify-query-defaults-live.mjs`；证据位于 `target/query-defaults-fix/`。
