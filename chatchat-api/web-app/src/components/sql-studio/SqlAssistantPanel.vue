<template>
  <aside class="sql-assistant-panel">
    <header><strong>AI 工程助手</strong><span>关联工程上下文</span></header>
    <div class="sql-assistant-scroll">
      <section class="sql-context-summary"><b>{{ mappingCount }} 个 Mapping</b><span> · {{ referenceIds.length }} 个 SQL 风格参考</span></section>
      <details class="sql-reference-picker" open><summary>历史 SQL 案例（已授权）</summary>
        <label v-for="file in references" :key="file.id"><input type="checkbox" :checked="referenceIds.includes(file.id)" @change="$emit('reference', file.id)" /><span :title="file.path">{{ file.path }}</span><small>v{{ file.version }}</small></label>
        <p v-if="!references.length">可在左侧上传参考 SQL，或选择已有工程源码。</p>
      </details>
      <label class="sql-agent-choice">Agent / Skills<select :value="agentId" @change="$emit('agent', $event.target.value)"><option value="">系统默认 Agent</option><option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option></select></label>
      <label class="sql-agent-choice">模型<select :value="modelName" @change="$emit('model', $event.target.value)"><option value="">系统默认模型</option><option v-for="model in models" :key="model.value" :value="model.value">{{ model.label || model.value }}</option></select></label>
      <form class="sql-assistant-prompt" @submit.prevent="$emit('generate')"><textarea :value="prompt" @input="$emit('prompt', $event.target.value)" maxlength="4000" placeholder="描述目标 SQL 工程、ETL 要求和需要参考的风格…"></textarea><button class="sql-primary" :disabled="busy || !mappingCount || !prompt.trim()">生成 SQL 工程</button></form>
      <section class="sql-generation-history" v-if="generations.length"><label>生成任务<select :value="generationId" @change="$emit('generation', $event.target.value)"><option v-for="(generation, index) in generations" :key="generation.id" :value="generation.id">{{ index === 0 ? '最近任务' : '历史任务 ' + (index + 1) }} · {{ generation.applied ? '已应用' : '待查看' }}</option></select></label></section>
      <section v-if="result" class="sql-generation-result">
        <div class="sql-task-status"><b>{{ result.task?.status || '待提交' }}</b><button v-if="!result.task" :disabled="busy" @click="$emit('resume')">继续提交</button><button v-else-if="!terminal" :disabled="busy" @click="$emit('cancel')">取消任务</button></div>
        <p v-if="result.task?.errorMessage" class="sql-inline-error">{{ result.task.errorMessage }}</p>
        <p v-if="result.outputError" class="sql-inline-error">{{ result.outputError }}</p>
        <p v-if="result.task?.status?.startsWith('WAIT_CONFIRM')">任务等待确认，可在现有任务管理中查看并处理。</p>
        <div v-for="file in result.files" :key="file.path" class="sql-proposed-file"><button @click="$emit('preview', file)">▤ {{ file.path }}</button><small>{{ file.content.length }} 字符</small></div>
        <button v-if="result.files.length && !result.generation.applied" class="sql-primary sql-apply" :disabled="busy" @click="$emit('apply')">确认保存生成文件</button>
        <p v-if="result.files.length" class="sql-validation-note">尚未执行 SQL 方言或数据库校验，请审核 Mapping 与生成脚本。</p>
        <details v-if="result.rawAnswer" class="sql-raw-answer"><summary>模型原始结果</summary><pre>{{ result.rawAnswer }}</pre></details>
        <details v-if="result.events.length" class="sql-task-events" open><summary>执行轨迹 · {{ result.events.length }} 条事件</summary><ol><li v-for="event in result.events.slice(-30)" :key="event.eventId || event.sequence"><b>{{ event.type }}</b><span>{{ event.toolName || event.status }}</span><small>#{{ event.sequence }}</small></li></ol></details>
      </section>
    </div>
  </aside>
</template>
<script setup>
import { computed } from 'vue';
import { terminalSqlTask } from '../../js/utils/sqlStudioState';
const props = defineProps({ mappingCount: Number, references: Array, referenceIds: Array, models: Array, agents: Array,
  agentId: String, modelName: String, prompt: String, busy: Boolean, generations: Array, generationId: String, result: Object });
defineEmits(['reference','agent','model','prompt','generate','generation','resume','cancel','preview','apply']);
const terminal = computed(() => terminalSqlTask(props.result?.task?.status));
</script>
