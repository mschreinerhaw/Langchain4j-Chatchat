# 任务列表中的证据失效隔离

2026-11-04：响应过滤器、知识探索源码和回归测试已整合进主源码，后续采用全量构建。
以下保留临时覆盖部署的背景记录；构建脚本现在直接读取主源码，不再维护重复副本。

服务器知识探索版本的 `KnowledgeResponseAdvice` 对整个 JSON 响应校验证据。
管理任务列表包含其他用户或证据已失效的历史任务时，一条记录即可使整页返回 403。

`TaskCollectionDisclosure` 对任务分页、旧版任务列表、运行概览的 latestTasks 逐条校验。
失效记录只返回允许列出的运行元数据和 `contentRestricted: true`，清除问题、回答、
错误、反馈、数据集及未来新增的内容字段。分页数量、顺序和正常记录保持原值。
任务详情、事件、计划和运行详情继续执行原有授权校验；保留的数据库审计不作修改。
前端收到受限记录时清空旧详情，不自动请求受限详情，并禁用反馈编辑。

## 源码来源与部署方式

本地已执行 `git fetch origin` 和 `git pull --ff-only`，当前最新分支提交为
`9eb2a9041a827a137982185cfebf41f7d2d6d518`，包含 origin/main。
服务器实际运行的知识探索补丁来自 `f6e1c3557d1c5925857fbb3d70016ab8ce66bef3`，
该提交不在当前远端中；其 `KnowledgeResponseAdvice` / `KnowledgeDisclosureGuard`
也尚未出现在本地主源码。直接全量部署本地分支会覆盖这些线上功能。

因此，新增的独立脱敏逻辑放在主源码，响应过滤器的精确替换源码放在本目录 `src`，
取自服务器 R12 发布包后只加入集合处理。`build.py` 针对 `baseline.json` 锁定的运行
JAR 编译并产生现有部署脚本支持的覆盖包，同时精确修改已部署任务页 bundle。
前端源代码中的同等修复保存在 `web-app/src/js/views/TasksView.js`。
补丁保留所有其他 JAR 条目的内容；部署脚本更新变更静态资源的时间戳，确保浏览器
重新校验时不会错误返回旧缓存。任务页 bundle 也使用新的文件名。

知识探索源码合入上游后，应将本目录 `src` 的响应过滤器改动合入同名主源码文件，
改回正常的全量构建流程。不要直接把本目录源码重复加入 Maven source roots。

## 构建与验证

在仓库根目录执行，使用 JDK 17：

```text
python deploy/patches/task-evidence-disclosure/build.py --baseline <运行JAR> --output target/task-evidence-overlay
python deploy/patches/task-evidence-disclosure/test.py --build-directory target/task-evidence-overlay --junit-console <junit-platform-console-standalone.jar> --spring-test <spring-test-6.2.8.jar>
```

后端 11 项回归覆盖混合分页、无引用的截断回答、运行概览、旧版列表、详情拒绝和异常传播。
在 `chatchat-api/web-app` 执行：

```text
npx.cmd vitest run src/js/views/TasksView.test.js src/services/apiAuth.test.js
```

前端及认证共 15 项测试通过。线上只读验收脚本为
`chatchat-api/web-app/scripts/test-task-evidence-live.mjs`，从环境变量
`TASK_VERIFY_ORIGIN`、`TASK_VERIFY_USER`、`TASK_VERIFY_PASSWORD` 读取登录信息；
报告和截图不保存密码或 token。该脚本验证登录、分页、筛选、搜索、权限边界和真实页面轮询。

## 已部署结果

2026-11-04，部署于 `192.168.195.221:18100`。
镜像：`runtime-os-api:task-evidence-disclosure-20261104-r1`。
发布目录：`/opt/chatchat-releases/task-evidence-disclosure-20261104-r1`。
部署报告及验收截图见 `docs/task-evidence-disclosure-20261104`。

仅替换 API 容器并重新绑定共享其网络命名空间的 node proxy；其他运行服务保持原容器。
原 API 和 node proxy 保留为停止状态的备份，发布目录保留原 JAR 和部署配置。
未登录请求仍应返回 401；受限历史内容的直接详情请求仍应返回 403。
