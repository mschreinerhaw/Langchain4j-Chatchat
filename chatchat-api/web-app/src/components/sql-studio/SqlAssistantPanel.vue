<template>
  <aside class="sql-assistant-panel sql-ide-assistant">
    <header><strong>AI 工程助手</strong><span>基于当前工程持续协作</span></header>
    <div ref="scroll" class="sql-assistant-scroll" role="log" aria-label="工程协作对话">
      <div v-if="!messages.length" class="sql-chat-empty"><strong>一起完成 SQL 工程</strong><p>引用 Mapping、SQL 案例或选中的代码，描述你要生成或修改的脚本。</p><small>任务由现有 Agent Runtime 执行，生成文件需确认后写入工程。</small></div>
      <article v-for="turn in messages" :key="turn.id" class="sql-chat-turn" :class="turn.role"><b>{{ turn.role === 'user' ? '我' : 'AI 工程助手' }}</b><p>{{ turn.text }}</p></article>
      <section v-if="result" class="sql-chat-result">
        <div class="sql-task-status"><b>{{ result.task?.status || '待提交' }}</b><button v-if="!result.task" :disabled="busy" @click="$emit('resume')">继续提交</button><button v-else-if="!terminal" :disabled="busy" @click="$emit('cancel')">取消</button></div>
        <p v-if="result.task?.errorMessage || result.outputError" class="sql-inline-error">{{ result.task?.errorMessage || result.outputError }}</p>
        <div v-for="file in result.files" :key="file.path" class="sql-proposed-file"><button @click="$emit('preview', file)">↔ {{ file.path }}</button><small>{{ file.content.length }} 字符</small></div>
        <button v-if="result.files.length && !result.generation.applied" class="sql-primary sql-apply" :disabled="busy" @click="$emit('apply')">确认并保存 {{ result.files.length }} 个文件</button>
        <p v-if="result.files.length" class="sql-validation-note">生成结果尚未执行 SQL 或数据库校验。</p>
        <details v-if="result.events.length" class="sql-task-events"><summary>Agent 执行轨迹 · {{ result.events.length }} 条</summary><ol><li v-for="event in result.events.slice(-30)" :key="event.eventId || event.sequence"><b>{{ event.type }}</b><span>{{ event.toolName || event.status }}</span><small>#{{ event.sequence }}</small></li></ol></details>
        <details v-if="result.rawAnswer" class="sql-raw-answer"><summary>模型原始结果</summary><pre>{{ result.rawAnswer }}</pre></details>
      </section>
    </div>
    <div class="sql-chat-context"><span>{{ mappingCount }} Mapping · {{ referenceIds.length }} 案例</span><span v-if="!mappingCount" class="sql-inline-error">生成任务需要至少一个 Mapping</span>
      <div v-if="selectedCode" class="sql-selection-chip">已选代码：{{ selectedCode.path }} · {{ selectedCode.text.length }} 字<button aria-label="移除代码引用" @click="$emit('clear-selection')">×</button></div>
      <details class="sql-chat-settings"><summary>工程上下文与模型设置</summary>
        <div class="sql-chat-file-picks"><button v-for="file in projectFiles" :key="file.id" type="button" @click="$emit('reference-file', file)">@{{ file.path }}</button><button v-for="file in references.filter(item => !projectFiles.some(projectFile => projectFile.id === item.id))" :key="file.id" type="button" @click="$emit('reference-file', file)">@{{ file.path }}</button></div>
        <label class="sql-agent-choice">Agent / Skills<select :value="agentId" @change="$emit('agent', $event.target.value)"><option value="">系统默认 Agent</option><option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option></select></label>
        <label class="sql-agent-choice">模型<select :value="modelName" @change="$emit('model', $event.target.value)"><option value="">系统默认模型</option><option v-for="model in models" :key="model.value" :value="model.value">{{ model.label || model.value }}</option></select></label>
        <label v-if="generations.length" class="sql-agent-choice">任务历史<select :value="generationId" @change="$emit('generation', $event.target.value)"><option v-for="(generation, index) in generations" :key="generation.id" :value="generation.id">{{ index === 0 ? '最近任务' : '历史任务 ' + (index + 1) }} · {{ generation.applied ? '已应用' : '待查看' }}</option></select></label>
      </details>
    </div>
    <form class="sql-chat-composer" @submit.prevent="send"><textarea :value="prompt" maxlength="4000" aria-label="发送给 AI 工程助手" placeholder="描述 SQL 工程需求，使用 @ 引用文件…" @input="$emit('prompt', $event.target.value)" @keydown.enter.exact.prevent="send"></textarea><div v-if="mentionOptions.length" class="sql-chat-mention-list"><button v-for="file in mentionOptions" :key="file.id" type="button" @click="chooseMention(file)">@{{ file.path }}</button></div><div><small>Enter 发送 · Shift+Enter 换行</small><button class="sql-primary" :disabled="busy || !mappingCount || !prompt.trim()">发送 ↗</button></div></form>
  </aside>
</template>
<script src="../../js/components/sql-studio/SqlAssistantPanel.js"></script>
