import AppNotice from '../../../components/AppNotice.vue';
import StudioCodePlan from '../../../components/editor/StudioCodePlan.vue';
import { computed, nextTick, ref, watch, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { sqlTaskPresentation } from '../../utils/sqlTaskPresentation';
import { terminalSqlTask } from '../../utils/sqlStudioState';
import { studioAiLabels, sqlGenerationAction } from '../../utils/studioAi';
import { buildPythonLineDiff } from '../../utils/pythonCodeDiff';
import { Sparkles, FileCode2, BookOpen, Send, AtSign, TrendingUp, CircleHelp, LoaderCircle, CircleCheck, Clock3, Plus } from '@lucide/vue';

export default {
  components: { StudioCodePlan, AppNotice, Sparkles, FileCode2, BookOpen, Send, AtSign, TrendingUp, CircleHelp, LoaderCircle, CircleCheck, Clock3, Plus },
  props: { mappingDocuments: { type:Array, default:() => [] }, mappingCount: Number, projectFiles: Array, references: Array, referenceIds: Array, models: Array, agents: Array,
    agentId: String, modelName: String, executionMode: String, prompt: String, messages: Array, busy: Boolean, generations: Array,
    generationId: String, result: Object, selectedCode: Object, currentFile: Object, dialectLabel: String, taskRunning: Boolean,
    pendingPhase: String, pendingStartedAt: Number, pollError: String, aiAction: String },
  emits: ['action', 'new-conversation', 'reference-file', 'open-resource', 'remove-mapping-document', 'choose-library', 'agent', 'model', 'execution-mode', 'prompt', 'send', 'generation', 'resume', 'cancel', 'preview', 'apply', 'clear-selection'],
  setup(props, { emit }) {
    const terminal = computed(() => terminalSqlTask(props.result?.task?.status));
    const scroll = ref(null);
    const allResources = ref(false), composer = ref(null), followOutput = ref(true), now = ref(Date.now());
    const resultAction = computed(() => sqlGenerationAction(props.result));
    const codePlan = computed(() => props.result?.generation?.prepared?.taskRequest?.toolInput?.studioCodePlan?.interpretationPlan);
    const codePlanState = computed(() => props.pendingPhase==='PREPARING' ? 'planning' : terminal.value
      ? ['SUCCESS','PARTIAL','PARTIAL_SUCCESS'].includes(props.result?.task?.status) ? 'complete' : 'failed'
      : ['RUNNING','WAIT_TOOL','WAIT_MODEL'].includes(props.result?.task?.status) ? 'exploring' : 'ready');
    const operationLabel = computed(() => studioAiLabels[props.pendingPhase ? props.aiAction : props.result ? resultAction.value : props.aiAction] || '代码生成');
    const resultNotes = computed(() => { try{return JSON.parse(props.result?.rawAnswer || '{}').notes || '';}catch{return '';} });
    const progress = computed(() => {
      const state=sqlTaskPresentation(props.result, props.pendingPhase, props.pendingStartedAt, now.value);
      return {...state,title:state.status==='IDLE' ? `${operationLabel.value} · 准备就绪` : `${operationLabel.value} · ${state.title}`,
        detail:resultAction.value==='explain' && state.status==='SUCCESS' && !props.result?.outputError ? '解释完成，工程代码保持原样。' : state.detail};
    });
    function fileDiff(file) { return buildPythonLineDiff((props.projectFiles || []).find(item=>item.path.toLowerCase()===file.path.toLowerCase())?.content || '',file.content); }
    let clock;
    const startClock = () => { clearInterval(clock); now.value = Date.now(); clock = setInterval(() => { now.value = Date.now(); }, 1000); };
    const stopClock = () => clearInterval(clock);
    onMounted(startClock); onActivated(startClock); onDeactivated(stopClock); onBeforeUnmount(stopClock);
    const quickActions = ['基于 Mapping 生成目标表建表与 ETL SQL', '解释这段 SQL 的作用', '帮我优化这段 SQL，提升性能',
      '参照已有 SQL 案例生成相同风格的脚本', '检查 SQL 是否有潜在问题'];
    const resources = computed(() => (props.projectFiles || []).filter(file => file.id !== props.currentFile?.id && ['MAPPING', 'REFERENCE', 'SQL'].includes(file.kind)));
    const shownResources = computed(() => allResources.value ? resources.value : resources.value.slice(0, 3));
    function useQuickAction(text) {
      emit('action',text.includes('解释') ? 'explain' : text.includes('优化') ? 'optimize' : text.includes('检查') ? 'fix' : 'generate');
      emit('prompt', `${props.currentFile ? '@' + props.currentFile.path + ' ' : ''}${text}`);
      nextTick(() => composer.value?.focus());
    }
    function insertMention() { emit('prompt', `${props.prompt || ''}${props.prompt && !/\s$/.test(props.prompt) ? ' ' : ''}@`); nextTick(() => composer.value?.focus()); }
    function newConversation() {
      if (props.busy || props.taskRunning || props.pendingPhase) return;
      emit('new-conversation'); followOutput.value = true;
      nextTick(() => { if (scroll.value) scroll.value.scrollTop = 0; composer.value?.focus(); });
    }
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
    return { codePlan, codePlanState, studioAiLabels, resultAction, resultNotes, operationLabel, fileDiff, newConversation, terminal, progress, trackScroll, mentionOptions, chooseMention, send, scroll, allResources, composer, quickActions, shownResources, resources, useQuickAction, insertMention };
  }
};
