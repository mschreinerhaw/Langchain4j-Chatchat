import { computed, nextTick, ref, watch } from 'vue';
import { terminalSqlTask } from '../../utils/sqlStudioState';

export default {
  props: { mappingCount: Number, projectFiles: Array, references: Array, referenceIds: Array, models: Array, agents: Array,
    agentId: String, modelName: String, executionMode: String, prompt: String, messages: Array, busy: Boolean, generations: Array,
    generationId: String, result: Object, selectedCode: Object },
  emits: ['reference-file', 'agent', 'model', 'execution-mode', 'prompt', 'send', 'generation', 'resume', 'cancel', 'preview', 'apply', 'clear-selection'],
  setup(props, { emit }) {
    const terminal = computed(() => terminalSqlTask(props.result?.task?.status));
    const scroll = ref(null);
    watch(() => [props.messages?.length, props.result?.events?.length], () => nextTick(() => { if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight; }));
    const mentionOptions = computed(() => {
      const query = /(?:^|\s)@([^\s]*)$/.exec(props.prompt || '')?.[1];
      if (query === undefined) return [];
      return [...(props.projectFiles || []), ...(props.references || [])]
        .filter((file, index, all) => all.findIndex(item => item.id === file.id) === index)
        .filter(file => file.path.toLowerCase().includes(query.toLowerCase())).slice(0, 6);
    });
    function chooseMention(file) { emit('reference-file', file); }
    function send() { if (!props.busy && props.mappingCount && props.prompt.trim()) emit('send'); }
    return { terminal, mentionOptions, chooseMention, send, scroll };
  }
};
