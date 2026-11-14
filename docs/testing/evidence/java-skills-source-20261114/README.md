# Java 前置 Skills 关闭：源码部署与验收记录

应用源码提交：`e96710f909a83852cc3e8af2b1d5289042e6c22f`。部署入口：`http://192.168.195.221:18100`。

## 最终范围

只关闭 Java 前置 Skills 发现、选择、正文加载、证据激活及 Skills 方法规划。分类器、详细任务规划、附件预解析、数据准备、调度、预算、权限和验收规则保留。`preserved-pipeline.json` 给出 7 个关键流程文件与原版本完全一致的 SHA-256。

生产 API/worker 设置 `CHATCHAT_RUNTIME_CONTROL_PLANE_JAVA_SKILLS_ENABLED=false`。Cline 获取已绑定 ID 引用，按需调用 `runtime__agent_skill_read`；Java 回调负责权限校验、内容传输及首次读取版本固定，不替 Cline 选择或应用方法。开关恢复 true 可回退新任务的前置 Skills 行为，已有任务不改写冻结合同。

## 服务器源码构建

1. 使用 `deploy/docker/source-stack/package-java-skills-source.py` 生成提交源码包，验证全部 2,617 个 Java 源文件入包，不包含本地应用 JAR/class。
2. 在服务器 `/opt/runtime-os/releases/java-skills-source-20261114/` 校验源码清单，从源码构建两个前端和 Java reactor；Maven 构建内运行相关测试。
3. 从同一源码构建 Native 控制镜像，运行 Node 测试。Node 历史证据完整性测试另补充仓库中的历史夹具，夹具不作为部署应用使用。
4. 公网 UI 使用服务器构建 JAR 中的前端资源，监控使用 `chatchat-api/src/main/resources/coding-monitor` 源码。API、worker 和 Native 节点更新；Executor、Recovery 与 Gateway 同步 Skill 工具协议及静态白名单，签名合同仍决定每个任务的实际权限。
5. API 容器替换时，同步重建所有共享其网络命名空间的 Temporal、worker、Node、Recovery、Studio 服务。原配置备份位于发布目录的 `deployment-backup/`，包含私有配置，未提交仓库。

详见 `server-build.json` 中的源码摘要、镜像 ID 和构建位置。初次构建的 JDK 路径、源码排除规则及测试夹具问题均保留在服务器日志中；最终服务器 Java 构建与测试通过，没有使用本地预编译包替换应用。

## 验证

- 服务器 Java：121 项通过，覆盖原 Agent/Role、附件、合同、取消恢复、Skills 关闭与按需读取路径。
- 服务器 Node：119 项，117 项通过、2 项按条件跳过、0 失败。
- 浏览器：主应用及监控实际登录成功，无主页面 JavaScript 异常。
- Cline 实际读取：找到一条服务器 Skill 读取凭证，读取正文 6,880 字节；记录版本与 SHA-256，未提交 Skill 正文。
- Python Studio、SQL Studio：新生成任务完成，SQL 提议文件 1 个；未应用到用户文件，也未声称执行了生成代码。
- 业务回归与复测结果见 `business-regression.json`。保留首次失败，不能用后续成功覆盖失败记录。

Java 初始 Skills 发现/正文加载为 0 由开关路径的 mock 无交互测试和部署中的 `DEFERRED_TO_CLINE` 记录共同验证；Cline 主动读取后的 Java 授权传输单独计量。Java 分类/规划模型调用保持原流程，未要求其归零。当前记录是功能回归，不是受控性能基准，不将模型耗时波动作为提速结论。

## 已知问题与部署初测记录

部署初测发现 Executor/Recovery 旧协议及白名单未同步，已全部对齐；监控初次使用了 Standalone 页面，已更正为 Java 权限门面的监控源码并完成登录测试。初测未分配成功的测试进程经过受限恢复通道确认 `neverAllocated=true`、`allocationFenced=true`，失败任务记录保留。

图表首次任务报 `Exactly one HTML report entry required`。这是保留的报告产物验收路径，未为本次 Skills 调整放宽或改写该规则。复测情况在业务记录中明确列出，不将此类失败计为成功。

?????????????????????????????????????????????? `507 ARTIFACT_STORAGE_FULL`?????????? 377,373,822 / 2,147,483,648 ??????????????????????????????????????????????????????????????????????
