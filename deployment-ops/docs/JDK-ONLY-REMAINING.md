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

2026-11-17 后续实测进展（不等于正式交付）：

- 完整6.2GB Native签名包由纯Java导入成功，恢复了用户清理的缺失镜像。
- Vue新建环境 `runtime-jdk-v1`，任务 `54ea84d86a934ac79fc1b63b21fe7faf` 成功，
  24组件运行；Native Core、容器内Python及OS/MCP身份验证通过。根目录
  `/mnt/disk01/ops/r/7221-21630f35ed0c`，入口19400/MCP19401。
- 通用协议1.0.2升级、更换真实服务容器、手动回滚、1.0.3真实健康失败自动回滚、
  应用停止/启动六项通过；不操作Runtime和基础服务。
- rc.4→rc.5 替换平台JAR，停止期间及重启后全部运行业务 ID/StartedAt 不变。
- rc.5：`bc21a055f094f55943c875bfe225bdc142eb017a34eb75ecae61df7863f5d8d6`，
  当前测试单元 `deployment-ops-jdk-only-qa-r5`。
- v2备份包含管理H2、执行H2和实例配置，实际下载、校验、离线Java恢复通过。
  运行中恢复明确拒绝；恢复后旧会话失效，配置及部署记录精确恢复，业务容器不变。
- 隔离TemporaryFileSystem测试确认缺挂载退出1，宿主真实挂载不变。
- 无Docker私有网络测试发现RFC2965带引号Cookie兼容问题，已修复且待新JAR重跑。
- 可选systemd注册已改为Java `--install-service`，`deploy/jdk` 提供纯JDK脚本；
  此新安装入口尚待服务器实际安装验证。

后续必须完成：Native升级与失败回滚；纯Java平台自升级/故障恢复；新安装入口验收；
无Docker场景重跑、完整回归、离线发行包安装及最终验收推送。
正式发布标签仍禁止创建。`deploy/native` 属于旧外部Python执行器的兼容安装目录，
新产品默认入口应采用 `deploy/jdk`，不能用旧安装器的验收替代新实现验收。

当前Java源码在 src/main/java/com/chatchat/deployment/{docker,execution,protocol}。
StandaloneRuntime 已选择 JdkExecutor，不再启动解释器。Maven及离线构建不向运行JAR复制Python执行器。
共享变更锁阻止互相干扰；平台关闭只终止HTTP服务/线程，不执行业务停止。
