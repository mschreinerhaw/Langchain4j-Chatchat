import { computed, nextTick, ref, watch } from 'vue';
import { terminalSqlTask } from '../../utils/sqlStudioState';
import { Sparkles, FileCode2, BookOpen, Send, AtSign, TrendingUp, CircleHelp } from '@lucide/vue';

export default {
  components: { Sparkles, FileCode2, BookOpen, Send, AtSign, TrendingUp, CircleHelp },
  props: { mappingDocuments: { type:Array, default:() => [] }, mappingCount: Number, projectFiles: Array, references: Array, referenceIds: Array, models: Array, agents: Array,
    agentId: String, modelName: String, executionMode: String, prompt: String, messages: Array, busy: Boolean, generations: Array,
    generationId: String, result: Object, selectedCode: Object, currentFile: Object, dialectLabel: String, taskRunning: Boolean },
  emits: ['reference-file', 'open-resource', 'remove-mapping-document', 'choose-library', 'agent', 'model', 'execution-mode', 'prompt', 'send', 'generation', 'resume', 'cancel', 'preview', 'apply', 'clear-selection'],
  setup(props, { emit }) {
    const terminal = computed(() => terminalSqlTask(props.result?.task?.status));
    const scroll = ref(null);
    const assistantTab = ref('conversation'), allResources = ref(false), composer = ref(null);
    const quickActions = computed(() => assistantTab.value === 'generation'
      ? ['基于 Mapping 生成目标表建表与 ETL SQL', '参照已有 SQL 案例生成相同风格的脚本', '将当前 SQL 按日报表的维度进行改写']
      : ['解释这段 SQL 的作用', '帮我优化这段 SQL，提升性能', '基于现有表结构生成相似的 SQL', '帮我生成对应的 Mapping 说明', '检查 SQL 是否有潜在问题', '将结果按日报表的维度进行改写']);
    const resources = computed(() => (props.projectFiles || []).filter(file => file.id !== props.currentFile?.id && ['MAPPING', 'REFERENCE', 'SQL'].includes(file.kind)));
    const shownResources = computed(() => allResources.value ? resources.value : resources.value.slice(0, 3));
    function useQuickAction(text) {
      emit('prompt', `${props.currentFile ? '@' + props.currentFile.path + ' ' : ''}${text}`);
      nextTick(() => composer.value?.focus());
    }
    function insertMention() { emit('prompt', `${props.prompt || ''}${props.prompt && !/\s$/.test(props.prompt) ? ' ' : ''}@`); nextTick(() => composer.value?.focus()); }
    watch(() => [props.messages?.length, props.result?.events?.length], () => nextTick(() => { if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight; }));
    const mentionOptions = computed(() => {
      const query = /(?:^|\s)@([^\s]*)$/.exec(props.prompt || '')?.[1];
      if (query === undefined) return [];
      return [...(props.projectFiles || []), ...(props.references || [])]
        .filter((file, index, all) => all.findIndex(item => item.id === file.id) === index)
        .filter(file => file.path.toLowerCase().includes(query.toLowerCase())).slice(0, 6);
    });
    function chooseMention(file) { emit('reference-file', file); }
    function send(event) { if (event?.isComposing) return; if (!props.busy && !props.taskRunning && props.prompt.trim()) emit('send'); }
    return { terminal, mentionOptions, chooseMention, send, scroll, assistantTab, allResources, composer, quickActions, shownResources, resources, useQuickAction, insertMention };
  }
};
