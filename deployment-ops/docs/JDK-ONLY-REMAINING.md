# 纯 JDK 续工入口

用户明确要求：只依赖 JDK，不预装 Python；启动入口 `java -jar`，网页初始化及协议部署。
此前 `1.1.0-jar-rc.4` 仍有 Python 执行器，仅作历史候选，不符合新验收。
当前纯 JDK 迁移尚未完成，禁止标记正式交付或停止于此检查点。

已写入并实测：JDK Unix Socket DockerClient、TAR、签名离线包/分片/镜像加载，
H2 ExecutionJournal、ResourceManager 确认/幂等/日志/指标/数据卷保护。
证据在 `evidence/jdk-only-20261117`，9项真实资源验收通过且原业务ID/StartedAt不变。
DeploymentService 和 JdkExecutor 已接入单 JAR；新实例 PATH=/nonexistent 启动与网页首次管理员创建通过。

实例：19270 / 19272，服务测试单元 deployment-ops-jdk-only-qa，数据
`/mnt/disk01/deployment-ops-v1-validation/jdk-jar-state`。原19240正式实例未替换。
当前服务器候选 JAR `deployment-ops-v1-validation/deployment-ops-1.1.0-jdk-rc.4.jar`，
测试单元 `deployment-ops-jdk-only-qa-r4`；以 PATH=/nonexistent 编译及运行。
SHA-256 `c7dfd1a4f29c70fc17a6fcc83e4acf0d3a37c1eaf922f1b60911debca8063bb8`。

通用协议网页部署已实测通过：旧QA配方缺少 Redis 镜像声明的 /data 映射，Java执行器已拒绝，
没有允许匿名卷落系统盘。预检补充提前检查，`tests/jdk/ProtocolFixture.java`
生成1.0.1真实签名配方，明确命名卷；使用新环境 jar-jdk-protocol-v2 重测。
旧失败任务26a6e054e7924a8a9ea07c8e36871764保留，不改写为成功。
新任务 `1a65618cc8a641759b5e85c635bee7ca` 成功，网页签名包导入、依赖任务、
用户填写凭据、配置注入、真实服务健康门禁通过；public task 不含密码。
用户清理镜像/业务容器后，2026-11-17 19:32 新基线为4个基础服务，非旧45容器基线。
已核对这4个基础服务 ID/StartedAt 在平台更新和新部署后不变，不能沿用旧证据声称45个仍在。
证据 `evidence/jdk-only-20261117/protocol-v2/`。

纯 JDK 本地介质入口：`java -jar ... --import-package FILE SHA256 [--image-cache TAR]`。
只能读取 /mnt/disk01 普通文件，利用同盘硬链接避免大包重复占用空间；仍由运行节点
执行完整签名、全量校验和及真实 Docker 导入。完整 Native 包正在实际验证，尚未判通过。
Java 原有13项测试通过，新增3项协议安全/依赖/凭据测试通过，Vue生产构建通过。

后续必须完成：通用协议恢复/升级/回滚联调；Native Runtime Java适配器
（Java适配器已实现，真实Native安装与升级尚待验收，不可伪报）；纯Java维护/备份恢复；
JDK源码构建入口替代目标机Python编译脚本；无Docker/缺挂载/重启隔离及全链路回归、离线包与最终推送。

当前Java源码在 src/main/java/com/chatchat/deployment/{docker,execution,protocol}。
StandaloneRuntime 已选择 JdkExecutor，不再启动解释器。Maven及离线构建不向运行JAR复制Python执行器。
共享变更锁阻止互相干扰；平台关闭只终止HTTP服务/线程，不执行业务停止。
