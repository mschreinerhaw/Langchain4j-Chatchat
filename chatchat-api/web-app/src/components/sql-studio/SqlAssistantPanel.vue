<template>
  <aside class="sql-assistant-panel sql-ide-assistant">
    <header><div class="sql-assistant-heading"><strong><Sparkles :size="22" />AI 工程助手</strong><button class="sql-new-conversation" type="button" aria-label="新建会话" :title="busy || taskRunning || pendingPhase ? '请等待当前任务结束后新建会话' : '新建会话'" :disabled="busy || taskRunning || !!pendingPhase" @click="newConversation"><Plus :size="18" /></button></div><span>基于当前工程，协助你完成 SQL 开发</span></header>
    <nav class="sql-ai-modes" aria-label="SQL AI 工作模式"><button v-for="mode in [{id:'explain',label:'代码解释'},{id:'generate',label:'代码生成'},{id:'fix',label:'代码修复'},{id:'optimize',label:'代码优化'}]" :key="mode.id" type="button" :aria-pressed="aiAction===mode.id" :class="{active:aiAction===mode.id}" :disabled="busy||taskRunning||!!pendingPhase" @click="$emit('action',mode.id)">{{ mode.label }}</button></nav>
    <div ref="scroll" class="sql-assistant-scroll" role="log" aria-label="工程协作对话" @scroll="trackScroll">
      <section class="sql-current-context"><strong>基于当前文件</strong><div v-if="currentFile"><FileCode2 :size="18" /><span :title="currentFile.path">{{ currentFile.path.split('/').at(-1) }}</span><small>{{ currentFile.kind === 'MAPPING' ? 'Mapping' : dialectLabel }}</small></div><p v-else>打开工程文件，将其作为协作上下文。</p></section>
      <section v-if="!messages.length" class="sql-assistant-suggestions"><h3>从一个开发需求开始</h3><button v-for="text in quickActions" :key="text" type="button" :disabled="busy || taskRunning" @click="useQuickAction(text)"><TrendingUp :size="15" /><span>{{ text }}</span></button></section>
      <section v-if="!messages.length && resources.length" class="sql-related-resources"><header><h3>相关资源</h3><button type="button" @click="allResources = !allResources">{{ allResources ? '收起' : '查看全部' }}</button></header><button v-for="file in shownResources" :key="file.id" type="button" @click="$emit('open-resource', file)"><FileCode2 :size="16" /><span :title="file.path">{{ file.path.split('/').at(-1) }}</span><small>{{ file.kind === 'MAPPING' ? 'Mapping' : file.kind === 'REFERENCE' ? '案例' : 'SQL' }}</small></button></section>
      <article v-for="turn in displayMessages" :key="turn.id" class="sql-chat-turn" :class="turn.role" :aria-label="turn.role==='user'?'用户消息':'AI 助手消息'">
        <b>{{ turn.role === 'user' ? '我' : 'AI 工程助手' }}{{ turn.action ? ' · '+studioAiLabels[turn.action] : '' }}</b>
        <p v-if="!isCurrentTurn(turn)||!progress.running" :class="{'sql-chat-error':turn.status==='failed'}" :role="turn.status==='failed'?'alert':undefined">{{ turn.text }}</p>
        <section v-if="isCurrentTurn(turn)" class="sql-assistant-progress" :class="{ 'is-running': progress.running }" aria-label="AI 分析进度">
          <div class="sql-task-status"><div role="status" aria-live="polite"><LoaderCircle v-if="progress.running" :size="16" class="sql-progress-spinner" /><CircleCheck v-else-if="progress.status === 'SUCCESS'" :size="16" /><CircleHelp v-else :size="16" /><b>{{ progress.title }}</b></div>
            <span v-if="progress.running || progress.terminal" class="sql-progress-time" aria-label="任务耗时"><Clock3 :size="12" />{{ progress.elapsedLabel }}</span>
            <button v-if="result && !result.task && !pendingPhase" :disabled="busy" @click="$emit('resume')">继续提交</button>
            <button v-else-if="result?.task && !terminal && !pendingPhase" :disabled="busy" @click="$emit('cancel')">取消</button>
            <button v-if="result?.task && !terminal && !pendingPhase && result?.generation?.prepared?.taskRequest?.toolInput?.codingWorkspaceProfile==='SQL_WORKSPACE_V2'" :disabled="busy" @click="$emit('resume')">恢复任务</button>
          </div>
          <p class="sql-progress-detail" role="status">{{ progress.detail }}</p>
          <small v-if="progress.running&&progress.elapsed>=30">仍在等待当前阶段返回结果；已有计划与执行进展会保留在本条消息中。</small>
          <small v-if="pollError" role="alert">进度更新暂时中断，正在重试；任务可能仍在运行。</small>
        </section>
        <StudioCodePlan v-if="turn.role==='assistant'&&(turn.plan||isCurrentTurn(turn)&&codePlan)" :plan="isCurrentTurn(turn)?codePlan||turn.plan:turn.plan" :state="isCurrentTurn(turn)?codePlanState:turn.status==='failed'?'failed':'complete'" />
        <ol v-if="turn.activity?.length" class="studio-message-activity" aria-label="请求处理记录"><li v-for="entry in turn.activity" :key="entry.id" :data-status="entry.status">{{ entry.title }}</li></ol>
        <ol v-if="(isCurrentTurn(turn)?progress.steps:turn.steps)?.length" class="studio-message-activity" aria-label="实际执行进展"><li v-for="step in (isCurrentTurn(turn)?progress.steps:turn.steps).slice(-12)" :key="step.id" :data-status="step.status">{{ step.title }}<small v-if="step.toolName"> · {{ step.toolName }}</small></li></ol>
        <small v-if="turn.planId" class="studio-message-id">计划编号：{{ turn.planId }}</small>
      <section v-if="isCurrentTurn(turn)&&result&&(result.files.length||result.rawAnswer||result.outputError||result.task?.errorMessage)" class="sql-chat-result">
        <strong class="sql-result-kind">{{ studioAiLabels[resultAction] }} · {{ result.generation.applied ? '已应用' : result.files.length ? '待审查' : progress.running ? '处理中' : progress.status==='SUCCESS' && !result.outputError ? '回答完成' : progress.title }}</strong>
        <AppNotice v-if="result.task?.errorMessage || result.outputError" kind="error">{{ result.task?.errorMessage || result.outputError }}</AppNotice>
        <div v-for="file in result.files" :key="file.path" class="sql-proposed-file"><button @click="$emit('preview', file)">{{ projectFiles.some(item=>item.path.toLowerCase()===file.path.toLowerCase()) ? '修改' : '新增' }} · {{ file.path }}</button><small><b class="sql-added-count">+{{ fileDiff(file).additions }}</b> / <b class="sql-deleted-count">−{{ fileDiff(file).deletions }}</b></small></div>
        <button v-if="result.files.length && !result.generation.applied" class="sql-primary sql-apply" :disabled="busy" @click="$emit('apply')">审查 {{ result.files.length }} 个文件变更</button>
        <AppNotice v-if="result.files.length">生成结果尚未执行 SQL 或数据库校验。</AppNotice>
        <details v-if="result.rawAnswer" class="sql-raw-answer"><summary>模型原始结果</summary><pre>{{ result.rawAnswer }}</pre></details>
      </section>
      </article>
    </div>
    <div class="sql-chat-context"><span>{{ mappingCount }} Mapping · {{ referenceIds.length }} 案例</span><span>Mapping 与案例均可选</span>
      <div v-for="item in mappingDocuments" :key="item.docId" class="sql-selection-chip">{{ item.fileName }}<button type="button" :aria-label="'移除 Mapping ' + item.fileName" @click="$emit('remove-mapping-document', item.docId)">×</button></div>
      <div v-if="selectedCode" class="sql-selection-chip">已选代码：{{ selectedCode.path }} · {{ selectedCode.text.length }} 字<button aria-label="移除代码引用" @click="$emit('clear-selection')">×</button></div>
      <details class="sql-chat-settings"><summary>工程上下文与执行设置</summary>
        <div class="sql-chat-file-picks"><button v-for="file in projectFiles" :key="file.id" type="button" @click="$emit('reference-file', file)">@{{ file.path }}</button><button v-for="file in references.filter(item => !projectFiles.some(projectFile => projectFile.id === item.id))" :key="file.id" type="button" @click="$emit('reference-file', file)">@{{ file.path }}</button></div>
        <div class="sql-execution-modes" role="group" aria-label="执行方式"><button type="button" :class="{ active: executionMode === 'agent' }" :aria-pressed="executionMode === 'agent'" @click="$emit('execution-mode', 'agent')">Agent</button><button type="button" :class="{ active: executionMode === 'model' }" :aria-pressed="executionMode === 'model'" @click="$emit('execution-mode', 'model')">模型</button></div>
        <label v-if="executionMode === 'agent'" class="sql-agent-choice">Agent / Skills<select :value="agentId" @change="$emit('agent', $event.target.value)"><option value="">默认编程助手</option><option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option></select></label>
        <label v-else class="sql-agent-choice">模型<select :value="modelName" @change="$emit('model', $event.target.value)"><option value="">系统默认模型</option><option v-for="model in models" :key="model.value" :value="model.value">{{ model.label || model.value }}</option></select></label>
        <label v-if="generations.length" class="sql-agent-choice">任务历史<select :value="generationId" :disabled="busy || taskRunning || !!pendingPhase" @change="$emit('generation', $event.target.value)"><option value="" disabled>选择历史任务</option><option v-for="(generation, index) in generations" :key="generation.id" :value="generation.id">{{ index === 0 ? '最近任务' : '历史任务 ' + (index + 1) }} · {{ generation.applied ? '已应用' : '待查看' }}</option></select></label>
      </details>
    </div>
    <form class="sql-chat-composer" @submit.prevent="send"><textarea ref="composer" :value="prompt" maxlength="4000" aria-label="发送给 AI 工程助手" placeholder="输入你的问题，或输入 / 选择快捷指令…" @input="$emit('prompt', $event.target.value)" @keydown.ctrl.enter.prevent="send" @keydown.meta.enter.prevent="send"></textarea><div v-if="prompt.startsWith('/')" class="sql-chat-mention-list"><button v-for="text in quickActions" :key="text" type="button" @click="useQuickAction(text)">{{ text }}</button></div><div v-if="mentionOptions.length" class="sql-chat-mention-list"><button v-for="file in mentionOptions" :key="file.id" type="button" @click="chooseMention(file)">@{{ file.path }}</button></div><div><div class="sql-composer-tools"><button type="button" aria-label="引用工程文件" title="引用工程文件" @click="insertMention"><AtSign :size="17" /></button><button type="button" aria-label="关联 Mapping 文档" title="关联 Mapping 文档" :disabled="busy" @click="$emit('choose-library', 'MAPPING')"><BookOpen :size="17" /></button></div><button class="sql-primary" :disabled="busy || taskRunning || !prompt.trim()"><Send :size="16" />{{ taskRunning ? '任务运行中' : '发送' }}</button></div></form><small class="sql-composer-hint">Ctrl / ⌘ + Enter 发送 · Enter 换行</small>
  </aside>
</template>
<script src="../../js/components/sql-studio/SqlAssistantPanel.js"></script>
