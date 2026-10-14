import AppNotice from '../../../components/AppNotice.vue';
import { computed, nextTick, ref, watch, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { sqlTaskPresentation } from '../../utils/sqlTaskPresentation';
import { terminalSqlTask } from '../../utils/sqlStudioState';
import { Sparkles, FileCode2, BookOpen, Send, AtSign, TrendingUp, CircleHelp, LoaderCircle, CircleCheck, Clock3 } from '@lucide/vue';

export default {
  components: { AppNotice, Sparkles, FileCode2, BookOpen, Send, AtSign, TrendingUp, CircleHelp, LoaderCircle, CircleCheck, Clock3 },
  props: { mappingDocuments: { type:Array, default:() => [] }, mappingCount: Number, projectFiles: Array, references: Array, referenceIds: Array, models: Array, agents: Array,
    agentId: String, modelName: String, executionMode: String, prompt: String, messages: Array, busy: Boolean, generations: Array,
    generationId: String, result: Object, selectedCode: Object, currentFile: Object, dialectLabel: String, taskRunning: Boolean,
    pendingPhase: String, pendingStartedAt: Number, pollError: String },
  emits: ['reference-file', 'open-resource', 'remove-mapping-document', 'choose-library', 'agent', 'model', 'execution-mode', 'prompt', 'send', 'generation', 'resume', 'cancel', 'preview', 'apply', 'clear-selection'],
  setup(props, { emit }) {
    const terminal = computed(() => terminalSqlTask(props.result?.task?.status));
    const scroll = ref(null);
    const allResources = ref(false), composer = ref(null), followOutput = ref(true), now = ref(Date.now());
    const progress = computed(() => sqlTaskPresentation(props.result, props.pendingPhase, props.pendingStartedAt, now.value));
    let clock;
    const startClock = () => { clearInterval(clock); now.value = Date.now(); clock = setInterval(() => { now.value = Date.now(); }, 1000); };
    const stopClock = () => clearInterval(clock);
    onMounted(startClock); onActivated(startClock); onDeactivated(stopClock); onBeforeUnmount(stopClock);
    const quickActions = ['基于 Mapping 生成目标表建表与 ETL SQL', '解释这段 SQL 的作用', '帮我优化这段 SQL，提升性能',
      '参照已有 SQL 案例生成相同风格的脚本', '检查 SQL 是否有潜在问题'];
    const resources = computed(() => (props.projectFiles || []).filter(file => file.id !== props.currentFile?.id && ['MAPPING', 'REFERENCE', 'SQL'].includes(file.kind)));
    const shownResources = computed(() => allResources.value ? resources.value : resources.value.slice(0, 3));
    function useQuickAction(text) {
      emit('prompt', `${props.currentFile ? '@' + props.currentFile.path + ' ' : ''}${text}`);
      nextTick(() => composer.value?.focus());
    }
    function insertMention() { emit('prompt', `${props.prompt || ''}${props.prompt && !/\s$/.test(props.prompt) ? ' ' : ''}@`); nextTick(() => composer.value?.focus()); }
    function trackScroll() { if (scroll.value) followOutput.value = scroll.value.scrollHeight - scroll.value.scrollTop - scroll.value.clientHeight < 64; }
    watch(() => [props.messages?.length, props.messages?.at(-1)?.text, props.result?.files?.length], () => nextTick(() => {
      if (scroll.value && followOutput.value) scroll.value.scrollTop = scroll.value.scrollHeight;
    }));
    const mentionOptions = computed(() => {
      const query = /(?:^|\s)@([^\s]*)$/.exec(props.prompt || '')?.[1];
      if (query === undefined) return [];
      return [...(props.projectFiles || []), ...(props.references || [])]
        .filter((file, index, all) => all.findIndex(item => item.id === file.id) === index)
        .filter(file => file.path.toLowerCase().includes(query.toLowerCase())).slice(0, 6);
    });
    function chooseMention(file) { emit('reference-file', file); }
    function send(event) { if (event?.isComposing) return; if (!props.busy && !props.taskRunning && props.prompt.trim()) emit('send'); }
    return { terminal, progress, trackScroll, mentionOptions, chooseMention, send, scroll, allResources, composer, quickActions, shownResources, resources, useQuickAction, insertMention };
  }
};
