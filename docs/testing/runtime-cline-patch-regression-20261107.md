# 28,866 字符补丁修复回归报告

状态：PASS（真实历史样本离线回归），不声称模型自主生成分段补丁或业务分析通过。

2026-11-07重新运行 `node scripts/verify-runtime-failure-repair.mjs target/runtime-data-remediation/private target/runtime-cline-acceptance-evidence/patch-regression.json`，退出0。使用原失败任务的实际模型工具payload和journal，未人为生成替代补丁。原任务 `analysis-coding-2b06d38d8a4b73634e8eed98cafd0ebd9c67d6eb514d7016687f5bbb1584043b`。

| 核对项 | 结果 |
|---|---|
| 原编辑old/new字符数 | 57/101、144/598、344/28866 |
| 合法分段 | 最后一个newText分15000/14026，后段160字符唯一锚点 |
| 单段上限 | 仍16000字符，原超限原子拒绝 |
| 原生Editor | 使用现有sdk-adapter.editProgram，不替换实现 |
| 父版本/结果版本 | 1→2，过期父版本拒绝 |
| 旧Hash | 过期Hash拒绝，不部分提交 |
| 原始代码SHA | `82ed4c33618a13317401161d6479188c04e7c3139a4ebfc0372b146d53e12b6f` |
| 预期/SDK最终SHA | `0b0901cbfc188fa3bcbc463cc9c3ee53ec724ccec97190cf9d6bf08e70eb1242`，完全相同 |
| 文件完整性 | 无关字节不变，失败原子性、checkpoint恢复通过 |
| 失败反馈 | editIndex、old/new长度、唯一锚点、行/偏移及建议分段；保持版本绑定 |

上轮实际Worker同一SDK0.0.93容器也回放通过，Worker镜像未变；本轮本机重跑原始输出保留于忽略target目录，最终脱敏JSON随验收归档。原始receipt Hash `65e8a9240a6d272e55a5845913b57a0c70b76f07bd6b10414eae5cbcaec83db2`，与旧证明相同。
