<template>
  <section class="sql-bottom-panel" aria-label="SQL Studio 底部面板">
    <nav class="sql-bottom-tabs" aria-label="底部面板标签">
      <button
        v-for="item in tabs"
        :key="item.id"
        type="button"
        :class="{ active: tab === item.id }"
        @click="$emit('tab', item.id)"
        >{{ item.label
        }}<small v-if="item.id === 'problems' && problemCount">{{ problemCount }}</small></button
      >
      <button
        class="sql-bottom-close"
        type="button"
        aria-label="关闭底部面板"
        @click="$emit('close')"
        >×</button
      >
    </nav>
    <div class="sql-bottom-content">
      <div v-if="tab === 'problems'"
        ><p v-if="!problemCount && !error">当前没有需要处理的问题。</p
        ><AppNotice v-if="problemCount"
          >编辑器发现 {{ problemCount }} 个语法或格式问题，请查看代码中的标记。</AppNotice
        ><AppNotice v-if="error" kind="error">{{ error }}</AppNotice></div
      >
      <div v-else-if="tab === 'output'"
        ><p v-if="!message && !result?.rawAnswer">暂无输出。</p
        ><AppNotice v-if="message" kind="success">{{ message }}</AppNotice
        ><pre v-if="result?.rawAnswer">{{ result.rawAnswer }}</pre>
      </div>
      <div v-else-if="tab === 'tasks'"
        ><p v-if="!generations.length">暂无工程任务。</p
        ><button
          v-for="(generation, index) in generations"
          :key="generation.id"
          class="sql-bottom-task"
          type="button"
          @click="$emit('generation', generation.id)"
          >任务 {{ generations.length - index }} ·
          {{ generation.applied ? "已应用" : generation.taskId ? "查看执行" : "待提交" }}</button
        ></div
      >
      <div v-else-if="tab==='diagnostics'" aria-label="SQL AI 运行诊断">
        <p v-if="result?.metadataExploration?.codingExploration?.autonomous">自主探索完成 {{ result.metadataExploration.codingExploration.toolCalls }} 次工具调用 · SQL 校验 {{ result.metadataExploration.codingExploration.validationAttempts }} 次 · 提案修复 {{ result.metadataExploration.codingExploration.repairAttempts }} 次 · 工具累计耗时 {{ result.metadataExploration.codingExploration.toolDurationMs }} ms · 最终 SQL {{ result.metadataExploration.codingExploration.finalSqlValid ? '已通过校验' : '尚未通过校验' }}</p>
        <p>模型调用和工具耗时独立记录；SQL 提案不代表已经执行。响应预览经过脱敏和长度限制。</p>
        <template v-if="result?.metadataExploration?.diagnostics?.schemaVersion">
          <p>本次尝试 {{ result.metadataExploration.diagnostics.elapsedMs }} ms · 授权核查 {{ result.metadataExploration.diagnostics.authorizationMs ?? '未记录' }} ms · 元数据工具 {{ result.metadataExploration.diagnostics.toolMs }} ms · 输出校验 {{ result.metadataExploration.diagnostics.outputValidationMs }} ms（含解析 {{ result.metadataExploration.diagnostics.outputParseMs ?? '未记录' }} ms） · 格式修复 {{ result.metadataExploration.diagnostics.repairCount }} 次</p>
          <details v-for="receipt in result.metadataExploration.diagnostics.modelResponses" :key="receipt.modelCall">
            <summary>模型调用 {{ receipt.modelCall }} · {{ receipt.durationMs }} ms · {{ receipt.responseKind }} · {{ receipt.validationCode || '未记录校验失败' }}</summary>
            <p>{{ receipt.providerModel || receipt.selectedModel }} · {{ receipt.protocol }} · 结束原因 {{ receipt.finishReason || 'SDK 未提供' }} · 输入 {{ receipt.inputTokens ?? '未提供' }} / 输出 {{ receipt.outputTokens ?? '未提供' }} Token</p>
            <p v-if="receipt.validationMessage">{{ receipt.validationMessage }}</p>
            <p>{{ receipt.responseChars }} 字符{{ receipt.previewTruncated ? '，预览已截断' : '' }} · 修复序号 {{ receipt.repairAttempt ?? '无' }}</p>
            <pre>{{ receipt.responsePreview || '没有文本内容（工具请求或 SDK 未提供）' }}</pre>
          </details>
        </template>
        <p v-else-if="result?.generation?.prepared?.taskRequest?.toolInput?.sqlCodingMetadata">此工程任务的模型和工具记录保留在现有任务监控中，以下为实际交付的元数据版本。</p>
        <p v-else>此任务尚无新版受控响应诊断记录。历史任务不会补造原始响应或 Token 数。</p>
        <details v-if="result?.generation?.prepared?.taskRequest?.toolInput?.sqlCodingMetadata?.tables?.length" aria-label="工程 SQL 元数据证据">
          <summary>工程 SQL 元数据证据 · {{ result.generation.prepared.taskRequest.toolInput.sqlCodingMetadata.tables.length }} 张表</summary>
          <div v-for="(table,index) in result.generation.prepared.taskRequest.toolInput.sqlCodingMetadata.tables" :key="index">
            <strong>{{ [table.database,table.schema,table.tableName].filter(Boolean).join('.') }}</strong>
            <p>发现上下文 {{ table.discoveryId || '历史记录未保留' }} · 绑定 {{ table.bindingId || '历史记录未保留' }}</p>
            <p>结构快照 {{ table.snapshotId }} · 表证据 {{ table.tableRef || '历史记录未保留' }} · 可见字段 {{ table.columns.length }} 个</p>
          </div>
          <small>此版本在提交任务时冻结；恢复和读取结果时仍重新核查授权及结构一致性。</small>
        </details>
      </div>
      <div v-else
        ><section v-if="structureReview" aria-label="SQL 结构审查">
          <p>静态校验不代表业务验收通过；数据库 SQL 未执行。</p>
          <p v-if="!structureReview.structureReviewRequired">本次静态检查未发现已覆盖的结构风险。业务口径仍需确认。</p>
          <p v-for="(issue,index) in structureReview.structuralDiagnostics" :key="index">
            {{ warningLabel(issue.code) }} · {{ issue.path }}<span v-if="issue.columns?.length"> · {{ issue.columns.join('、') }}</span>
          </p>
          <p v-for="(link,index) in structureReview.relationships" :key="`link-${index}`">
            {{ link.left || link.alias }}<span v-if="link.right"> → {{ link.right }}</span>：{{ link.status === 'DECLARED_FOREIGN_KEY' ? '已匹配完整外键结构证据（业务含义仍需确认）' : '关联尚未验证，不能仅凭字段同名确认' }}
          </p>
          <p v-for="(grain,index) in structureReview.grains" :key="`grain-${index}`">
            {{ grain.tableName || grain.alias }}：{{ grain.status === 'DECLARED_KEY' ? `声明主键 ${grain.primaryKeys.join('、')}` : '未获得完整主键证据' }}；业务粒度未确认。
          </p>
        </section
        ><p>{{
          result?.validation === "NOT_RUN" || !result
            ? "尚未执行 SQL 方言或数据库校验。"
            : result.validation
        }}</p
        ><p>SQL 文件的生成与编辑不会执行数据库语句。</p></div
      >
    </div>
  </section>
</template>
<script src="../../js/components/sql-studio/SqlBottomPanel.js"></script>
