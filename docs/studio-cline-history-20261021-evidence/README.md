# Studio 联机与历史展示脱敏证据

- [SQL 真模型回执](sql-verified-acceptance.json)、[Python 真模型回执](python-verified-acceptance.json)：模型身份、Token、代码哈希和会话定位。
- [真实浏览器](studio-history-browser.json)：已部署 UI / API，无 HTTP 夹具；两个条目及全部源文件可见，查看不会创建生成任务。
- [任务收尾](post-validation.json)：全部 6 个受理任务停止；保留初始失败和中间样本；生成后至展示修复的执行组件哈希未改变。
- [定向测试](directed-tests.json)：Java 132、前端 103，重跑去重；SDK 跨进程夹具和真实供应商调用分别标明。
- [全量测试](full-regression.json)、[失败差异](full-regression-delta.json)：全量未通过，依赖模块因原生崩溃未运行。
- [源代码依赖审计](source-audit.json)、[场景字面量检查](production-literal-check.json)、[代码变更](code-changes.txt)。
- [最终部署](deployment.json)、[组包哈希](module-package.json)、[模块](module-manifest.json)、[Web 文件哈希](web-files.json)、[解析器镜像](parser-image.json)、[网络边界](boundary.json)。
- `first-candidate-*`、`before-source-history-*`、`before-literal-render-*` 保留各候选的部署和组包元数据，不混用版本。

原始输入、生成源码、模型消息、认证信息及完整历史保留在受控私有目录。这里只保存验收需要的脱敏指标、哈希和失败用例类型。语法校验没有执行生成代码，不能当作业务正确性认证或生产放行。
