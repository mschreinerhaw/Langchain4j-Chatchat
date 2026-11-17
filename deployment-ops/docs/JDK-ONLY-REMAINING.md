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

最新检查点补充：

- rc.6 源码 `0f9dad45f`，服务器JAR SHA-256
  `795f46cf1a17331392e0279930180342d036a06fd1479a99c46022673db80b90`。
  当前19270测试单元 `deployment-ops-jdk-only-qa-r6`；rc.5于20:29收到SIGTERM退出，
  完整上传 `80b64294475e41dd80b298ce984f697a` 已从网页API重新校验，无需重传。
- 私有网络/不可访问DockerSocket真实启动、首次创建管理员、H2配置持久化、
  重启后登录及主机实际指标两轮通过；rc.6 JAR检查无 `.py` 资源。
- Native真实升级到1.0.1-image-qa成功，任务 `627f19a2162844fea62552974601c02c`，
  gateway已换成 `sha256:0fc453892edf427a59ab96bcd2599793d98d95a83edb70bf5588bcf3cfcc4e56`，
  三个执行器Unix健康接口均HEALTHY且resourceEnforced=true。失败升级介质正在导入。
- 新增纯Java systemd独立维护进程：验证发行包、冻结业务变更、保存两H2及实例配置、
  平台JAR切换、真实PID/健康门禁、失败快照恢复、显式中断恢复入口；仍待服务安装后的实测。
- 新操作文档 `JDK-OPERATIONS.md`，离线包默认README改为此入口；旧兼容源码仍保留。

继续入口：完成新检查点源码服务器编译，`--install-service` 注册独立QA服务19290/19292，
通过网页初始化后执行实际平台升级/失败升级回退；不要覆盖原19240正式服务或已验证Runtime数据。

继续进展：Native失败升级任务 `95ae648f80d341e6baf1f175e6c6071b` 已自动回滚至1.0.1，
再通过任务 `627f19a2162844fea62552974601c02c` 手动回滚至初始版本；两项及三个Unix健康门禁通过。
新systemd服务首次测试发现 Tomcat docbase 使用系统临时目录且 Spring 启动失败后节点仍存活；
已修复为显式数据盘 docbase、启动失败关闭内部节点、JVM启动参数前置临时目录，16项Java测试重跑通过。
旧失败QA服务 `deployment-ops-jdk-clean-qa` 已停止并禁用，保留其文件；不能将其记为安装验收通过。
下一候选需以新实例 `deployment-ops-jdk-clean-v2-qa`、新数据目录重新验收19290/19292。

上述新实例安装与网页首次初始化已通过。源码993e38a2e的rc.8 JAR
`5cf29156371f661664aaeadc1aad6a1e326bcd0f888e2875de1138b6ba890e07`，
完整候选ZIP `target/releases/deployment-ops-1.1.0-jdk-rc.8.zip`。
该实例通过网页完整ZIP上传实际升级到rc.9（JAR
`a30cd48942c0d77ebc77ce693eae11342afb407d5868501c38553b8d92d6b9cb`），
任务 `9cb707e149574bd89b4720f1d61858c4` 成功。再上传真实无效端口配置的失败JAR发行包，
任务 `95bb610234814a3f84eb314b4bda8a88` 失败并自动恢复rc.9及两个H2，原会话、配置和业务容器不变。

当前新增完整原生安装合同：发布器在无网络/无DockerSocket/无客户目录的容器中调用
受信源码纯渲染方法，输出24组件的依赖、ENTRYPOINT、CMD。Java新增contractVersion=1
校验及实际容器argv比对；支持 `$${VAR}` 原样传递容器变量，不重新解释用户密码。
新签名完整Runtime包 `/mnt/disk01/ops-publisher-20261116/runtime-os-1.0.3-protocol-20261117.ops.zip`，
SHA256 `296d3b1757755df1a3f1acb12488aa64be1a634649a4a25132511542f37138c9`。
用同盘CoW复用已验证镜像压缩字节，没有修改原包、镜像归档或私钥。新协议实际升级验收尚待执行。

21:24回归：Java17项、Python兼容51项通过；Vue格式检查与生产构建通过。
恢复入口：编译下一候选、在现有JDK Runtime管理数据目录注册纯Java系统服务
`deployment-ops-jdk-runtime-qa`（19270/19272），停止旧测试平台进程但保持业务运行。
验证新合同实际升级，然后完成GUI资源回归、服务维护中断恢复、最终离线包安装及发行报告。
正式标签仍未发布。

后续实测全部通过：完整24组件合同网页执行任务 `7309586762064801bafe43db0f610894`，
逐组件实际镜像/ENTRYPOINT/CMD核对通过；所有原业务容器ID和状态不变。Java执行器GUI资源17项、
权限监控6项、专用QA镜像删除/冷导入/版本标签3项均通过。
服务GUI恢复任务 `4f803213f7c04838ac7a82ba82ea506f` 通过，配置回退且旧会话失效。
维护任务 `87853c03eb5b4b32bf720af25dad89e5` 在真实health_check阶段被定向SIGKILL测试，
随后 `--recover-maintenance` 恢复原平台、两个H2与配置，通过PID健康门禁，业务容器不变。

当前新增已实测存储修复：已验证暂存ZIP回收；无原始暂存ZIP时重新校验持久化签名和所有负载，
审计确认删除专用QA镜像后，能从持久化归档真正冷导入恢复。JDK `StagingProbe` 通过，
29个运行业务的ID/StartedAt不变。失败或中断上传保留；不回收用户原始介质或业务数据。
下一步编译部署包含此修复的候选，在现有实例回收历史已验证暂存；已结束QA发行原始包
仅在校验及确认不被活动版本使用后清理，保留对应持久化签名和镜像缓存，释放安装验收空间。
随后从完整离线包在新实例做源码构建、首次初始化和完整合同冷部署，完成最终发行。

当前Java源码在 src/main/java/com/chatchat/deployment/{docker,execution,protocol}。
StandaloneRuntime 已选择 JdkExecutor，不再启动解释器。Maven及离线构建不向运行JAR复制Python执行器。
共享变更锁阻止互相干扰；平台关闭只终止HTTP服务/线程，不执行业务停止。
