<template>
  <aside class="sql-assistant-panel sql-ide-assistant">
    <header
      ><div class="sql-assistant-heading"
        ><strong><Sparkles :size="22" />{{ aiAction === 'explore' ? 'AI 数据库助手' : 'AI 工程助手' }}</strong
        ><button
          class="sql-new-conversation"
          type="button"
          aria-label="新建会话"
          :title="busy || taskRunning || pendingPhase ? '请等待当前任务结束后新建会话' : '新建会话'"
          :disabled="busy || taskRunning || !!pendingPhase"
          @click="newConversation"
          ><Plus :size="18" /></button></div
      ><span>{{ aiAction === 'explore' ? '探索真实元数据，分析问题并审查 SQL 提案' : '基于当前工程，协助你完成 SQL 开发' }}</span></header
    >
    <div
      ref="scroll"
      class="sql-assistant-scroll"
      role="log"
      aria-label="工程协作对话"
      @scroll="trackScroll"
    >
      <slot name="database-context" />
      <nav class="sql-assistant-workbench" aria-label="数据库助手工作区">
        <span>AI 对话</span><button type="button" :disabled="currentFile?.kind !== 'SQL' || busy" @click="$emit('publication')">模板管理</button>
      </nav>
      <section class="sql-current-context"
        ><strong>基于当前文件</strong
        ><div v-if="currentFile"
          ><FileCode2 :size="18" /><span :title="currentFile.path">{{
            currentFile.path.split("/").at(-1)
          }}</span
          ><small>{{ currentFile.kind === "MAPPING" ? "Mapping" : dialectLabel }}</small></div
        ><p v-else>打开工程文件，将其作为协作上下文。</p></section
      >
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
        <div v-if="turn.role==='assistant' && (!isCurrentTurn(turn) || !progress.running)" class="sql-ai-markdown" v-html="renderReportNarrative(turn.text)"></div>
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
              v-else-if="result?.task && !terminal && !pendingPhase"
              :disabled="busy"
              @click="$emit('cancel')"
              >取消</button
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
        <SqlMetadataEvidence v-if="turn.role==='assistant'" :project-id="projectId" :evidence="isCurrentTurn(turn)?result?.metadataExploration:turn.metadataExploration" />
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
            result.task?.errorMessage || result.outputError
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
            ></div
          >
          <button
            v-if="result.files.length && !result.generation.applied"
            class="sql-primary sql-apply"
            :disabled="busy"
            @click="$emit('apply')"
            >审查 {{ result.files.length }} 个文件变更</button
          >
          <AppNotice v-if="result.files.length">生成结果尚未执行 SQL 或数据库校验。</AppNotice>
          <details v-if="result.rawAnswer" class="sql-raw-answer"
            ><summary>模型原始结果</summary><pre>{{ result.rawAnswer }}</pre>
          </details>
        </section>
      </article>
    </div>
    <div class="sql-chat-context">
      <details class="sql-chat-settings"
        ><summary>高级设置</summary>
        <div v-if="selectedCode" class="sql-selection-chip"
          >已选代码：{{ selectedCode.path }} · {{ selectedCode.text.length }} 字<button
            aria-label="移除代码引用"
            @click="$emit('clear-selection')"
            >×</button
          ></div
        >
        <div v-for="document in userDocuments" :key="document.fileId" class="sql-selection-chip"
          >{{ document.fileName }} ·
          {{ document.purpose === "EVIDENCE" ? "分析依据" : "参考规则" }}</div
        >
        <slot name="context-documents" />
        <div
          v-if="availableAgents.length"
          class="sql-execution-modes"
          role="group"
          aria-label="执行方式"
          ><button
            type="button"
            :class="{ active: executionMode === 'agent' }"
            :aria-pressed="executionMode === 'agent'"
            @click="$emit('execution-mode', 'agent')"
            >Agent</button
          ><button
            type="button"
            :class="{ active: executionMode === 'model' }"
            :aria-pressed="executionMode === 'model'"
            @click="$emit('execution-mode', 'model')"
            >模型</button
          ></div
        >
        <label v-if="availableAgents.length && executionMode === 'agent'" class="sql-agent-choice"
          >Agent<select :value="agentId" @change="$emit('agent', $event.target.value)"
            ><option value="" disabled>选择已发布 Agent</option
            ><option v-for="agent in availableAgents" :key="agent.id" :value="agent.id">{{
              agent.name
            }}</option></select
          ></label
        >
        <label v-else class="sql-agent-choice"
          >模型<select :value="modelName" @change="$emit('model', $event.target.value)"
            ><option value="">系统默认模型</option
            ><option v-for="model in models" :key="model.value" :value="model.value">{{
              model.label || model.value
            }}</option></select
          ></label
        >
        <label v-if="generations.length" class="sql-agent-choice"
          >任务历史<select
            :value="generationId"
            :disabled="busy || taskRunning || !!pendingPhase"
            @change="$emit('generation', $event.target.value)"
            ><option value="" disabled>选择历史任务</option
            ><option
              v-for="(generation, index) in generations"
              :key="generation.id"
              :value="generation.id"
              >{{ index === 0 ? "最近任务" : "历史任务 " + (index + 1) }} ·
              {{ generation.applied ? "已应用" : "待查看" }}</option
            ></select
          ></label
        >
      </details>
    </div>
    <form class="sql-chat-composer" @submit.prevent="send">
      <label class="sql-assistant-action">协作方式<select :value="aiAction" :disabled="busy || taskRunning" aria-label="SQL 助手方式" @change="$emit('action',$event.target.value)">
        <option value="explore">数据库问答与 SQL 探索</option><option value="generate">工程代码生成</option><option value="explain">解释当前 SQL</option><option value="fix">修复 SQL</option><option value="optimize">优化 SQL</option>
      </select></label>
      <textarea
        ref="composer"
        :value="prompt"
        maxlength="4000"
        aria-label="发送给 AI 工程助手"
        placeholder="输入你的问题或 SQL 开发需求…"
        @input="$emit('prompt', $event.target.value)"
        @keydown.ctrl.enter.prevent="send"
        @keydown.meta.enter.prevent="send"
      ></textarea
      ><div class="sql-composer-actions"
        ><button class="sql-primary" :disabled="busy || taskRunning || !prompt.trim()"
          ><Send :size="16" />{{ taskRunning ? "任务运行中" : "发送" }}</button
        ></div
      ></form
    ><small class="sql-composer-hint">Ctrl / ⌘ + Enter 发送 · Enter 换行</small>
  </aside>
</template>
<script src="../../js/components/sql-studio/SqlAssistantPanel.js"></script>
