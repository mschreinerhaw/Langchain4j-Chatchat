<template>
  <aside class="sql-assistant-panel sql-ide-assistant">
    <header>
      <div class="sql-assistant-heading">
        <strong><Sparkles :size="18" />AI 数据库助手</strong>
        <div class="sql-assistant-header-actions">
          <button type="button" aria-label="历史会话" title="历史会话" :disabled="!generations.length" @click="showDialog(historyDialog)"><History :size="16" /></button>
          <button
          class="sql-new-conversation"
          type="button"
          aria-label="新建会话"
          :title="busy || taskRunning || pendingPhase ? '请等待当前任务结束后新建会话' : '新建会话'"
          :disabled="busy || taskRunning || !!pendingPhase"
          @click="newConversation"
          ><Plus :size="17" /></button>
        </div>
      </div>
      <slot name="database-context" />
    </header>
    <div
      ref="scroll"
      class="sql-assistant-scroll"
      role="log"
      aria-label="工程协作对话"
      @scroll="trackScroll"
    >
      <section v-if="!displayMessages.length" class="sql-chat-empty-state" aria-label="数据库助手建议">
        <Database :size="28" />
        <h3>今天想处理什么 SQL 问题？</h3>
        <p>我可以探索数据库、生成 SQL、分析问题或优化查询</p>
        <button type="button" :disabled="busy || taskRunning" @click="suggest('explore','探索当前数据库有哪些业务表')"><Search :size="17" /><span><strong>探索数据库</strong><small>查找表、字段与关联关系</small></span><ChevronRight :size="15" /></button>
        <button type="button" :disabled="busy || taskRunning" @click="suggest('explore','请根据当前数据库的真实表结构生成 SQL，查询需求：')"><FileCode2 :size="17" /><span><strong>生成 SQL</strong><small>根据自然语言生成查询</small></span><ChevronRight :size="15" /></button>
        <button type="button" :disabled="busy || taskRunning || currentFile?.kind !== 'SQL'" @click="suggest('explain','请解释当前 SQL 的逻辑，并检查潜在问题。',true)"><Wrench :size="17" /><span><strong>分析当前 SQL</strong><small>解释、排查和优化代码</small></span><ChevronRight :size="15" /></button>
      </section>
      <article
        v-for="turn in displayMessages"
        :key="turn.id"
        class="sql-chat-turn"
        :class="turn.role"
        :aria-label="turn.role === 'user' ? '用户消息' : 'AI 助手消息'"
      >
        <b
          >{{ turn.role === "user" ? "我" : turn.action === 'explore' ? 'AI 数据库助手' : "AI 工程助手"
          }}{{ turn.action ? " · " + studioAiLabels[turn.action] : "" }}</b
        >
        <SqlAssistantMessage v-if="turn.role==='assistant' && (!isCurrentTurn(turn) || !progress.running)" :content="turn.text"
          :insertable="canInsertTurn(turn)" :replaceable="canInsertTurn(turn) && editorSelectionAvailable"
          @insert="$emit('insert-sql',$event)" @replace="$emit('replace-sql',$event)" />
        <p
          v-else-if="turn.role==='user'"
          :class="{ 'sql-chat-error': turn.status === 'failed' }"
          :role="turn.status === 'failed' ? 'alert' : undefined"
          >{{ turn.text }}</p
        >
        <section
          v-if="isCurrentTurn(turn)"
          class="sql-assistant-progress"
          :class="{ 'is-running': progress.running }"
          aria-label="AI 分析进度"
        >
          <div class="sql-task-status"
            ><div role="status" aria-live="polite"
              ><LoaderCircle
                v-if="progress.running"
                :size="16"
                class="sql-progress-spinner"
              /><CircleCheck v-else-if="progress.status === 'SUCCESS'" :size="16" /><CircleHelp
                v-else
                :size="16"
              /><b>{{ [progress.progressLabel, progress.title].filter(Boolean).join(' · ') }}</b></div
            >
            <span
              v-if="progress.running || progress.terminal"
              class="sql-progress-time"
              aria-label="任务耗时"
              ><Clock3 :size="12" />{{ progress.elapsedLabel }}</span
            >
            <button
              v-if="result && !result.task && !pendingPhase"
              :disabled="busy"
              @click="$emit('resume')"
              >继续提交</button
            >
            <button
              v-if="
                result?.task &&
                !terminal &&
                !pendingPhase &&
                result?.generation?.prepared?.taskRequest?.toolInput?.codingWorkspaceProfile ===
                  'SQL_WORKSPACE_V2'
              "
              :disabled="busy"
              @click="$emit('resume')"
              >恢复任务</button
            >
          </div>
          <p class="sql-progress-detail" role="status">{{ progress.detail }}</p>
          <small v-if="progress.running && progress.elapsed >= 30"
            >仍在等待当前阶段返回结果；已有计划与执行进展会保留在本条消息中。</small
          >
          <small v-if="pollError" role="alert"
            >进度更新暂时中断，正在重试；任务可能仍在运行。</small
          >
        </section>
        <section v-if="isCurrentTurn(turn) && progress.metadataGroups?.length" class="sql-metadata-operations" aria-label="元数据实际执行进展">
          <details v-for="group in progress.metadataGroups" :key="group.id" :data-state="group.state">
            <summary><LoaderCircle v-if="group.state==='RUNNING'" class="sql-progress-spinner" :size="13" />{{ group.title }} · {{ group.count }} 次 · {{ (group.elapsedMs/1000).toFixed(1) }} 秒 · {{ {RUNNING:'进行中',FAILED:'含失败记录',CANCELLED:'已取消',STOPPED:'未取得完成记录',COMPLETED:'已完成'}[group.state] }}</summary>
            <ol><li v-for="operation in group.operations" :key="operation.id">{{ operation.id }} · {{ operation.state }} · {{ operation.elapsedMs }}ms<span v-if="operation.reused"> · 复用已验证证据</span><span v-if="operation.reason"> · {{ operation.reason }}</span></li></ol>
          </details>
        </section>
        <button v-if="canRetry(turn)" type="button" class="sql-retry-question" :disabled="busy || taskRunning || !!pendingPhase" @click="retry(turn)"><RefreshCw :size="13" />重试请求</button>
        <SqlMetadataEvidence v-if="turn.role==='assistant'" :project-id="projectId" :locked="busy || taskRunning || !!pendingPhase" :evidence="isCurrentTurn(turn)?result?.metadataExploration:turn.metadataExploration" @reference="$emit('reference-metadata',$event)" />
        <small v-if="isCurrentTurn(turn) && result?.metadataExploration?.stats" class="sql-metadata-stats">本次尝试：模型 {{ result.metadataExploration.stats.modelCalls }} 次 · 工具选择 {{ result.metadataExploration.stats.toolCalls }} 次 · 复用 {{ result.metadataExploration.stats.reusedEvidenceCount }} 份证据 · 耗时 {{ (result.metadataExploration.stats.totalMs/1000).toFixed(1) }} 秒</small>
        <button v-if="isCurrentTurn(turn) && result?.metadataExploration?.candidates?.length && terminal" type="button" @click="followupSql">据此生成 SQL</button>
        <StudioCodePlan
          v-if="turn.role === 'assistant' && (turn.plan || (isCurrentTurn(turn) && codePlan))"
          :plan="isCurrentTurn(turn) ? codePlan || turn.plan : turn.plan"
          :state="
            isCurrentTurn(turn) ? codePlanState : turn.status === 'failed' ? 'failed' : 'complete'
          "
        />
        <ol v-if="turn.activity?.length" class="studio-message-activity" aria-label="请求处理记录"
          ><li v-for="entry in turn.activity" :key="entry.id" :data-status="entry.status">{{
            entry.title
          }}</li></ol
        >
        <ol
          v-if="(isCurrentTurn(turn) ? progress.steps : turn.steps)?.length"
          class="studio-message-activity"
          aria-label="实际执行进展"
          ><li
            v-for="step in (isCurrentTurn(turn) ? progress.steps : turnProgressSteps(turn))"
            :key="step.id"
            :data-status="step.status"
            >{{ step.number }}/{{ step.total }} {{ step.title }} · {{ {done:'已完成',active:'进行中',error:'执行失败',cancelled:'已取消',timeout:'已超时',pending:'未开始',unrecorded:'未单独记录',partial:'部分完成',stopped:'已终止',waiting:'等待确认',empty:'需要补充输入'}[step.status] || '等待中' }}<small v-if="step.detail">{{ step.detail }}</small></li
          ></ol
        >
        <small v-if="turn.planId" class="studio-message-id">计划编号：{{ turn.planId }}</small>
        <section
          v-if="
            isCurrentTurn(turn) &&
            result &&
            (result.files.length ||
              result.rawAnswer ||
              result.outputError ||
              result.task?.errorMessage)
          "
          class="sql-chat-result"
        >
          <strong class="sql-result-kind"
            >{{ studioAiLabels[resultAction] }} ·
            {{
              result.generation.applied
                ? "已应用"
                : result.files.length
                  ? "待审查"
                  : progress.running
                    ? "处理中"
                    : progress.status === "SUCCESS" && !result.outputError
                      ? "回答完成"
                      : progress.title
            }}</strong
          >
          <AppNotice v-if="!progress.running && (result.task?.errorMessage || result.outputError)" kind="error">{{
            sqlAssistantError(result.task?.errorMessage || result.outputError)
          }}</AppNotice>
          <div v-for="file in result.files" :key="file.path" class="sql-proposed-file"
            ><button @click="$emit('preview', file)"
              >{{
                projectFiles.some((item) => item.path.toLowerCase() === file.path.toLowerCase())
                  ? "修改"
                  : "新增"
              }}
              · {{ file.path }}</button
            ><small
              ><b class="sql-added-count">+{{ fileDiff(file).additions }}</b> /
              <b class="sql-deleted-count">−{{ fileDiff(file).deletions }}</b></small
            ><SqlAssistantMessage v-if="file.path.toLowerCase().endsWith('.sql') && !hasSql(turn.text,file.content)" :sql="file.content"
              :insertable="canInsertTurn(turn)" :replaceable="canInsertTurn(turn) && editorSelectionAvailable"
              @insert="$emit('insert-sql',$event)" @replace="$emit('replace-sql',$event)" /></div
          >
          <button
            v-if="result.files.length && !result.generation.applied"
            class="sql-primary sql-apply"
            :disabled="busy"
            @click="$emit('apply')"
            >审查 {{ result.files.length }} 个文件变更</button
          >
          <AppNotice v-if="result.files.length">生成结果尚未执行 SQL 或数据库校验。</AppNotice>

        </section>
      </article>
    </div>
    <Teleport to="body"><dialog ref="settingsDialog" class="sql-assistant-dialog" aria-label="AI 助手高级设置" @click.self="closeDialog(settingsDialog)">
      <header><h3>高级设置</h3><button type="button" aria-label="关闭高级设置" @click="closeDialog(settingsDialog)"><X :size="17" /></button></header>
      <div class="sql-chat-context"><div class="sql-chat-settings">
        <section class="sql-settings-model" aria-label="模型配置"><h4>模型配置</h4>
          <p class="sql-settings-help">选择本次协作使用的 Agent 或模型，配置更改即时生效。</p>
          <div v-if="availableAgents.length" class="sql-execution-modes" role="group" aria-label="执行方式">
            <button type="button" :class="{active:executionMode==='agent'}" :aria-pressed="executionMode==='agent'" @click="$emit('execution-mode','agent')">Agent</button>
            <button type="button" :class="{active:executionMode==='model'}" :aria-pressed="executionMode==='model'" @click="$emit('execution-mode','model')">模型</button>
          </div>
          <div v-if="availableAgents.length && executionMode==='agent'" class="sql-agent-choice"><span>Agent</span><SqlSearchSelect :model-value="agentId" label="选择已发布 Agent" placeholder="选择已发布 Agent" :options="availableAgents.map(agent=>({value:agent.id,label:agent.name}))" @update:model-value="$emit('agent',$event)" /></div>
          <div v-else class="sql-agent-choice"><span>模型</span><SqlSearchSelect :model-value="modelName" label="选择模型" :options="[{value:'',label:'系统默认模型'},...models.map(model=>({value:model.value,label:model.label || model.value}))]" @update:model-value="$emit('model',$event)" /></div>
        </section>
        <section class="sql-settings-context" aria-label="上下文配置"><h4>上下文配置</h4><p class="sql-settings-help">文档和参考脚本仅用于本次任务的授权上下文。</p>
        <button type="button" @click="$emit('diagnostics');closeDialog(settingsDialog)">查看本次运行诊断</button>
        <div v-for="document in userDocuments" :key="document.fileId" class="sql-selection-chip"
          >{{ document.fileName }} ·
          {{ document.purpose === "EVIDENCE" ? "分析依据" : "参考规则" }}</div
        >
        <slot name="context-documents" :close-settings="() => closeDialog(settingsDialog)" />
        </section>
      </div></div>
    </dialog>
    <dialog ref="historyDialog" class="sql-assistant-dialog" aria-label="SQL 历史会话" @click.self="closeDialog(historyDialog)">
      <header><h3>历史会话</h3><button type="button" aria-label="关闭历史会话" @click="closeDialog(historyDialog)"><X :size="17" /></button></header>
      <div class="sql-assistant-history"><button v-for="(generation,index) in generations" :key="generation.id" type="button"
        :aria-current="generation.id === generationId ? 'true' : undefined" :disabled="busy || taskRunning || !!pendingPhase"
        @click="chooseHistory(generation.id)"><strong>{{ historyTitle(generation,index) }}</strong><small>{{ generation.applied ? '已应用到工程' : '查看会话与结果' }}</small></button></div>
    </dialog></Teleport>
    <footer class="sql-assistant-input">
    <form class="sql-chat-composer" @submit.prevent="send">
      <div v-if="selectedCode || includeCurrentFile" class="sql-composer-context">
        <FileCode2 :size="13" /><button type="button" class="sql-context-file-link" :title="(selectedCode?.path || currentFile?.path) + ' · 最多提供前 900 字符'" @click="$emit('view-file')">{{ selectedCode ? '已选 SQL · ' + Math.min(900,selectedCode.text.length) + ' 字符' + (selectedCode.text.length > 900 ? '（已截取）' : '') : currentFile?.path }}</button>
        <button type="button" aria-label="移除 SQL 上下文" @click="clearSqlContext"><X :size="13" /></button>
      </div>
      <div class="sql-composer-box">
      <textarea
        ref="composer"
        rows="3"
        :value="prompt"
        maxlength="4000"
        aria-label="发送给 AI 工程助手"
        placeholder="描述需求，或让 AI 分析当前 SQL…"
        @input="inputPrompt($event.target.value)"
        @keydown.ctrl.enter.prevent="send"
        @keydown.meta.enter.prevent="send"
      ></textarea></div>
      <div class="sql-composer-actions">
        <label class="sql-assistant-action"><select :value="aiAction" :disabled="busy || taskRunning" aria-label="SQL 助手方式" @change="$emit('action',$event.target.value)">
          <option value="explore">数据库探索</option><option value="generate">工程代码生成</option><option value="explain">解释 SQL</option><option value="fix">修复 SQL</option><option value="optimize">优化 SQL</option>
        </select></label>
        <button type="button" class="sql-attach-current-file" :disabled="!editorEditable || busy || taskRunning" :aria-pressed="includeCurrentFile" aria-label="附加当前 SQL" :title="includeCurrentFile ? '取消附加当前 SQL' : '附加当前 SQL（最多 900 字符）'" @click="$emit('include-file',!includeCurrentFile)"><FileCode2 :size="14" /><span>当前文件</span></button>
        <button type="button" class="sql-composer-settings" aria-label="高级设置" title="高级设置" @click="showDialog(settingsDialog)"><Settings2 :size="15" /></button>
        <span class="sql-composer-spacer"></span>
        <button v-if="taskRunning || pendingPhase" type="button" class="sql-stop-question" aria-label="停止任务" :disabled="busy || !result?.task || terminal" :title="result?.task ? '停止当前任务' : '任务提交后可停止'" @click="$emit('cancel')"><Square :size="12" /><span>停止</span></button>
        <button v-else class="sql-primary sql-send-question" :disabled="!canSend" aria-label="发送" title="Ctrl / ⌘ + Enter 发送"><span>发送</span><ArrowUp :size="15" /></button>
      </div></form>
      <small class="sql-composer-hint">Ctrl / Cmd + Enter 发送</small>
    </footer>
  </aside>
</template>
<script src="../../js/components/sql-studio/SqlAssistantPanel.js"></script>
<style scoped src="../../styles/components/sql-studio/sql-assistant-dialog.css"></style>
