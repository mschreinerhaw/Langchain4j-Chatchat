# Runtime 最低模型硬件与上下文要求

文档版本：1.0  
生效日期：2026-08-06

## 1. 最低结论

ChatChat Runtime 的部署基线分为两个等级：

| 等级 | 模型 | 用途 | 是否允许作为通用生产模型 |
| --- | --- | --- | --- |
| 开发最低配置 | 7B～8B INT4，128K上下文 | 接口联调、工具调用测试、简单总结 | 否 |
| 生产最低配置 | 30B～32B INT4，128K有效上下文 | DAG规划、工具选择、Evidence Loop、最终综合 | 是 |

生产环境最低要求为：

> **30B～32B模型、INT4权重量化、128K有效上下文、80GB级总GPU显存。**

7B 模型虽然能够调用工具和完成简单总结，但复杂判断、计划调整、冲突证据分析和长链路因果关系容易出错，因此只作为开发联调最低配置。

## 2. 模型推理节点最低硬件

### 2.1 开发联调最低配置

| 资源 | 最低要求 |
| --- | --- |
| 模型 | 7B～8B，INT4 |
| GPU | 1×24GB显存 |
| CPU | 8核/16线程 |
| 系统内存 | 64GB |
| 本地磁盘 | 200GB NVMe SSD |
| 上下文 | 128K，单并发 |
| KV Cache | FP8优先；不支持时使用模型兼容类型并重新核算显存 |
| 操作系统 | Linux x86_64 |

该配置只保证开发联调能力，不保证复杂 Agent 任务质量和生产吞吐。

### 2.2 通用生产最低配置

| 资源 | 最低要求 |
| --- | --- |
| 模型 | 30B～32B，INT4/AWQ/GPTQ等经过验收的4-bit权重 |
| GPU | 1×80GB，或2×48GB显存 |
| CPU | 24物理核或等效算力 |
| 系统内存 | 128GB |
| 本地磁盘 | 500GB NVMe SSD |
| 网络 | 单机单卡无特殊要求；多卡或模型服务分离建议10GbE以上 |
| 上下文 | 128K有效上下文 |
| 最大并发 | 最低保证1个128K活动会话；更高并发必须压测后配置 |
| KV Cache | 推荐FP8 |
| 计算类型 | 推荐BF16；硬件不支持时使用FP16 |
| 操作系统 | Linux x86_64 |

这里的80GB级显存是模型推理节点总显存，不是INT4权重文件大小。显存还必须容纳KV Cache、量化元数据、CUDA工作区、临时张量和推理框架。

对于2×48GB部署，推理引擎必须支持目标模型的Tensor Parallel，并验证跨卡通信、吞吐和长上下文稳定性。两张显卡显存不能在不启用模型并行的情况下自动合并。

### 2.3 Runtime平台与模型部署在同一节点

如果模型推理服务、ChatChat Runtime、MCP Server、数据库连接池和本地Evidence Store部署在同一台服务器，最低建议提升为：

| 资源 | 最低要求 |
| --- | --- |
| GPU | 1×80GB或2×48GB |
| CPU | 32物理核 |
| 系统内存 | 192GB |
| 本地磁盘 | 1TB NVMe SSD |
| 网络 | 10GbE |

生产环境更建议将模型推理服务与Runtime/MCP服务分离部署，避免长上下文Prefill占满CPU、内存或GPU时影响工具执行和审计写入。

## 3. 其他模型规模参考

以下数据作为模型规模、显存容量和Runtime适用范围的评估依据，但不能代替目标模型实测：

| 模型规模 | INT4权重占用 | 128K单会话建议显存 | Runtime定位 |
| --- | ---: | ---: | --- |
| 7B～8B | 5～7GB | 16～24GB | 仅开发、流程联调，不建议生产复杂分析 |
| 14B | 9～12GB | 24～40GB | 最低可用规格，适合简单工具调用 |
| 30B～32B | 19～24GB | 48～80GB | 推荐生产规格，规划和证据综合更稳定 |
| 70B～72B | 42～50GB | 80～120GB或2×48GB | 高质量复杂诊断、长链路推理 |
| MoE模型 | 取决于总参数量 | 按总权重和KV Cache计算 | 速度可能更好，但不能只按激活参数估算显存 |

70B模型使用FP16时，仅权重的理论占用约为：

```text
700亿参数 × 2 bytes ≈ 140GB
```

使用INT4后权重通常约40～50GB，但加上128K KV Cache和运行开销后，仍建议使用80～120GB总显存或多卡部署。

## 4. 显存计算要求

部署容量必须按下面的组成计算：

```text
所需GPU显存 = 模型权重
            + 单会话KV Cache × 最大活动会话数
            + 推理框架和计算工作区
            + 量化元数据及临时张量
            + 15%～20%安全余量
```

需要特别注意：

1. INT4通常只表示模型权重量化，不代表KV Cache也是INT4；
2. 128K是最大序列长度，不代表可以同时运行多个128K会话；
3. 并发数每增加一个，都会增加活动序列的KV Cache需求；
4. 不同模型的层数、Attention Head和GQA结构不同，KV Cache可能相差数倍；
5. 48GB能装入32B INT4权重，不代表一定能稳定运行完整128K上下文；
6. 必须用目标模型、目标量化文件和目标推理引擎执行显存压测。

## 5. 最低上下文要求

### 5.1 模型上下文

上下文窗口按以下等级作为模型选型和任务容量评估依据：

| 等级 | 上下文窗口 | 建议场景 |
| --- | ---: | --- |
| 最低 | 128K tokens | 1～2轮 Evidence Loop、普通工具结果 |
| 推荐 | 200K～256K tokens | 3轮以上 Evidence Loop、多工具诊断 |
| 高负载 | 256K以上 | 大型表结构、批量巡检、长报告综合 |

上下文等级表示单次模型请求能够承载的资料规模，不代表模型推理能力等级。模型规模和上下文等级必须分别评估，再根据任务复杂度组合选择。

| 参数 | 最低要求 | 推荐值 |
| --- | ---: | ---: |
| 模型标称上下文 | 128K tokens | 200K～256K tokens |
| 经测试的有效上下文 | 不低于100K tokens | 不低于目标配置的80% |
| 最大模型输出 | 12K tokens | 16K～30K tokens |
| Evidence Loop | 至少支持3轮 | 支持多轮压缩与按需召回 |

“标称128K”不能直接视为合格。必须验证关键约束或Evidence分别位于上下文开头、中间和末尾时，模型仍能正确召回、关联并用于最终结论。

### 5.2 128K最低配置

128K上下文应按以下预算拆分：

| 上下文组成 | Token预算 |
| --- | ---: |
| System Prompt和Runtime协议 | 16K |
| 多轮会话历史 | 20K |
| 模型输出预留 | 12K |
| Tool Schema和Evidence | 80K |
| 合计 | 128K |

对应配置：

```yaml
chatchat:
  models:
    context-window-max-tokens: 128000
    context-reserved-system-tokens: 16000
    context-reserved-history-tokens: 20000
    context-reserved-output-tokens: 12000
    chatModels:
      runtime-model:
        maxTokens: 12000
```

### 5.3 200K推荐配置

当前Runtime默认采用200K预算：

| 上下文组成 | Token预算 |
| --- | ---: |
| System Prompt和Runtime协议 | 20K |
| 多轮会话历史 | 30K |
| 模型输出预留 | 30K |
| Tool Schema和Evidence | 120K |
| 合计 | 200K |

对应配置：

```yaml
chatchat:
  models:
    context-window-max-tokens: 200000
    context-reserved-system-tokens: 20000
    context-reserved-history-tokens: 30000
    context-reserved-output-tokens: 30000
    chatModels:
      runtime-model:
        maxTokens: 30000
```

如果模型服务实际只允许8K或16K输出，必须同步修改`context-reserved-output-tokens`和`maxTokens`，不能保留不真实的30K输出预算。

## 6. Evidence与长期记忆要求

128K上下文不是长期存储。三轮以上Evidence Loop必须使用：

```text
当前任务短期上下文
  + Evidence确定性压缩
  + 完整Evidence外置存储
  + Evidence ID和摘要引用
  + 按需召回
```

要求：

- 完整工具结果保存在Runtime Evidence Store；
- Rewrite只读取有界压缩视图；
- 压缩不得改变工具成功、失败、缺失和冲突状态；
- 不得因为某段Evidence未放入当前Prompt就判定证据不存在；
- 不得通过不断拼接全部历史来模拟长期记忆；
- 用户、租户和运行实例之间的Evidence必须隔离。

## 7. 推理引擎最低参数

以vLLM一类推理引擎为例，32B/128K最低部署可以从以下参数开始压测：

```bash
--max-model-len 131072
--gpu-memory-utilization 0.90
--kv-cache-dtype fp8
--max-num-seqs 1
--enable-prefix-caching
```

参数要求：

- `max-model-len`必须与Runtime的`context-window-max-tokens`一致或更大；
- 首次部署将`max-num-seqs`设置为1，确认128K稳定后再增加并发；
- 如果出现KV Cache不足或抢占，应降低并发和批处理Token，不能只缩短Runtime超时；
- 多卡部署时必须明确配置Tensor Parallel或Pipeline Parallel；
- GPU显存利用率不得设置为100%，应保留运行时安全空间。

## 8. 最低验收测试

硬件和模型上线前至少完成：

1. 32K、64K、100K和128K上下文请求测试；
2. 128K单会话连续执行10次，不出现OOM；
3. 三轮Evidence Loop能够完成计划、工具调用、重写和最终综合；
4. 关键Evidence分别位于Prompt开头、中部和尾部时仍能正确召回；
5. 连续运行24小时无持续显存或系统内存泄漏；
6. 达到目标并发时无伪成功、跨请求证据混合或不可恢复的服务异常；
7. 记录首Token延迟、总耗时、输入/输出Token、GPU显存和KV Cache使用率。

如果1×80GB或2×48GB无法通过目标模型的128K测试，应增加GPU资源、采用更小模型、降低并发或使用经过验证的KV Cache量化，不得把Runtime上下文配置成模型服务无法稳定承载的数值。

## 9. 最终采购与部署口径

### 开发环境

```text
7B～8B INT4
1×24GB GPU
8核CPU
64GB内存
200GB NVMe
128K上下文，单并发
```

### 通用生产最低环境

```text
30B～32B INT4
1×80GB GPU或2×48GB GPU
24核CPU
128GB内存
500GB NVMe
128K有效上下文
12K最大输出
单个128K活动会话起步，扩容并发必须实测
```

### 复杂任务推荐环境

```text
70B级INT4模型
80～120GB总GPU显存或多卡
32核以上CPU
192GB以上内存
1TB NVMe
200K～256K上下文
Evidence压缩和长期记忆外置
```

本文件定义的是最低容量基线。最终硬件数量必须根据实际模型结构、量化格式、上下文长度、最大并发和响应时间SLA压测确定。
