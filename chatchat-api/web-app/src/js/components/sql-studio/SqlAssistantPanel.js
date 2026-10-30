import AppNotice from '../../../components/AppNotice.vue';
import SqlSearchSelect from '../../../components/sql-studio/SqlSearchSelect.vue';
import SqlMetadataEvidence from '../../../components/sql-studio/SqlMetadataEvidence.vue';
import SqlAssistantMessage from '../../../components/sql-studio/SqlAssistantMessage.vue';
import {sqlAssistantParts} from '../../utils/sqlAssistantContent';
import {sqlTaskStatus,sqlMetadataProgress} from '../../utils/sqlMetadataProgress';
import StudioCodePlan from '../../../components/editor/StudioCodePlan.vue';
import { computed, nextTick, ref, watch, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue';
import { sqlTaskPresentation } from '../../utils/sqlTaskPresentation';
import { businessTaskProgress } from '../../utils/runtimeObservationPresentation';
import { terminalSqlTask } from '../../utils/sqlStudioState';
import { studioAiLabels, sqlGenerationAction } from '../../utils/studioAi';
import { buildPythonLineDiff } from '../../utils/pythonCodeDiff';
import { sqlAssistantAnswer,sqlAssistantError } from '../../utils/sqlIdeState';
import { Sparkles, FileCode2, CircleHelp, LoaderCircle, CircleCheck, Clock3, Plus, History, X,
  Database, ArrowUp, Settings2, RefreshCw, Search, Wrench, ChevronRight, Square } from '@lucide/vue';

export default {
  components: { SqlSearchSelect, SqlAssistantMessage, SqlMetadataEvidence, StudioCodePlan, AppNotice, Sparkles, FileCode2, CircleHelp, LoaderCircle, CircleCheck, Clock3, Plus,
    History, X, Database, ArrowUp, Settings2, RefreshCw, Search, Wrench, ChevronRight, Square },
  props: { userDocuments: { type:Array, default:()=>[] }, mappingDocuments: { type:Array, default:() => [] }, mappingCount: Number, projectFiles: Array, references: Array, referenceIds: Array, models: Array, agents: Array,
    agentId: String, modelName: String, executionMode: String, prompt: String, messages: Array, busy: Boolean, generations: Array,
    generationId: String, result: Object, selectedCode: Object, currentFile: Object, dialectLabel: String, taskRunning: Boolean,
    pendingPhase: String, pendingStartedAt: Number, pollError: String, aiAction: String, projectId: String,
    includeCurrentFile: Boolean, editorEditable: Boolean, editorSelectionAvailable: Boolean, contextReady: {type:Boolean,default:true} },
  emits: ['reference-metadata','diagnostics','view-file','insert-sql','replace-sql','include-file','retry','action', 'new-conversation', 'reference-file', 'open-resource', 'remove-mapping-document', 'choose-library', 'agent', 'model', 'execution-mode', 'prompt', 'send', 'generation', 'resume', 'cancel', 'preview', 'apply', 'clear-selection'],
  setup(props, { emit }) {
    const availableAgents=computed(()=>(props.agents||[]).filter(agent=>agent.marketStatus?.toLowerCase()==='published'));
    const terminal = computed(() => terminalSqlTask(sqlTaskStatus(props.result?.task)));
    const scroll = ref(null);
    const composer = ref(null), followOutput = ref(true), now = ref(Date.now());
    const settingsDialog=ref(null), historyDialog=ref(null);
    const canSend=computed(()=>!props.busy && !props.taskRunning && !props.pendingPhase && !!props.prompt.trim()
      && (props.aiAction!=='explore' || props.contextReady));
    const resultAction = computed(() => sqlGenerationAction(props.result));
    const codePlan = computed(() => props.result?.generation?.prepared?.taskRequest?.toolInput?.studioCodePlan?.interpretationPlan);
    const codePlanState = computed(() => props.pendingPhase==='PREPARING' ? 'planning' : terminal.value
      ? ['SUCCESS','PARTIAL','PARTIAL_SUCCESS'].includes(props.result?.task?.status) ? 'complete' : 'failed'
      : ['RUNNING','WAIT_TOOL','WAIT_MODEL'].includes(props.result?.task?.status) ? 'exploring' : 'ready');
    const operationLabel = computed(() => studioAiLabels[props.pendingPhase ? props.aiAction : props.result ? resultAction.value : props.aiAction] || '代码生成');
    const progress = computed(() => {
      if(props.aiAction==='explore' && props.pendingPhase)return sqlMetadataProgress(props.result?.events,props.result?.task,props.pendingPhase,props.pendingStartedAt,now.value);
      const state=sqlTaskPresentation(props.result, props.pendingPhase, props.pendingStartedAt, now.value);
      return {...state,title:state.status==='IDLE' ? `${operationLabel.value} · 准备就绪` : `${operationLabel.value} · ${state.title}`,
        detail:resultAction.value==='explain' && state.status==='SUCCESS' && !props.result?.outputError ? '解释完成，工程代码保持原样。' : state.detail};
    });
    const displayMessages = computed(() => {
      const messages = props.messages || [];
      const id = props.result?.generation?.id;
      if (!id || messages.some(turn => turn.role === 'assistant' && turn.generationId === id)) return messages;
      const question=props.result?.generation?.prepared?.taskRequest?.toolInput?.studioUserRequest || props.result?.task?.question;
      const restored=question && !messages.some(turn=>turn.role==='user' && turn.generationId===id)?[{id:`user:${id}`,generationId:id,role:'user',action:resultAction.value,text:question}]:[];
      return [...messages, ...restored, { id:`assistant:${id}`, generationId:id, role:'assistant', action:resultAction.value,
        text:sqlAssistantAnswer(props.result), plan:codePlan.value }];
    });
    const currentTurnId = computed(() => props.result?.generation?.id
      ? displayMessages.value.find(turn => turn.role === 'assistant' && turn.generationId === props.result.generation.id)?.id
      : props.pendingPhase ? displayMessages.value.at(-1)?.id : null);
    const isCurrentTurn = turn => turn.role === 'assistant' && turn.id === currentTurnId.value;
    function canInsertTurn(turn){
      if(!props.editorEditable)return false;
      if(isCurrentTurn(turn))return !progress.value.running && ['SUCCESS','PARTIAL','PARTIAL_SUCCESS'].includes(progress.value.status)
        && !props.result?.outputError && (resultAction.value==='explore'?props.result?.metadataExploration?.fieldValidation==='CHECKED':!!props.result?.files?.length);
      return turn.metadataExploration?.fieldValidation==='CHECKED' && turn.status!=='failed';
    }
    const turnProgressSteps = turn => businessTaskProgress((turn.steps || []).filter(step => !['pending','unrecorded'].includes(step.status)).map(step => step.number
      ? { businessProgress: { index: step.number - 1, status: step.status, reason: step.detail } } : step), {status: turn.status}).steps;
    function fileDiff(file) { return buildPythonLineDiff((props.projectFiles || []).find(item=>item.path.toLowerCase()===file.path.toLowerCase())?.content || '',file.content); }
    let clock;
    const startClock = () => { clearInterval(clock); now.value = Date.now(); clock = setInterval(() => { now.value = Date.now(); }, 1000); };
    const stopClock = () => clearInterval(clock);
    onMounted(startClock); onActivated(startClock); onDeactivated(stopClock); onBeforeUnmount(stopClock);
    function newConversation() {
      if (props.busy || props.taskRunning || props.pendingPhase) return;
      emit('new-conversation'); followOutput.value = true;
      nextTick(() => { if (scroll.value) scroll.value.scrollTop = 0; composer.value?.focus(); });
    }
    function trackScroll() { if (scroll.value) followOutput.value = scroll.value.scrollHeight - scroll.value.scrollTop - scroll.value.clientHeight < 64; }
    watch(() => [props.messages?.length, props.messages?.at(-1)?.text, props.result?.files?.length, props.result?.rawAnswer, props.result?.task?.canonicalState, props.pendingPhase, progress.value.steps.length,props.result?.events?.length], () => nextTick(() => {
      if (scroll.value && followOutput.value) scroll.value.scrollTop = scroll.value.scrollHeight;
    }));
    function send(event) { if (event?.isComposing) return; if (canSend.value) { followOutput.value=true;emit('send'); } }
    function showDialog(dialog){if(!dialog || dialog.open)return;if(typeof dialog.showModal==='function')dialog.showModal();else dialog.setAttribute('open','');}
    function closeDialog(dialog){if(!dialog)return;if(typeof dialog.close==='function' && dialog.open)dialog.close();dialog.removeAttribute('open');}
    function chooseHistory(id){if(props.busy || props.taskRunning || props.pendingPhase)return;emit('generation',id);closeDialog(historyDialog.value);}
    function historyTitle(generation,index){return generation.prepared?.taskRequest?.toolInput?.studioUserRequest?.slice(0,100) || (index===0?'最近会话':'历史会话 '+(index+1));}
    function suggest(action,text,attach=false){if(props.busy || props.taskRunning)return;emit('action',action);emit('prompt',text);if(attach)emit('include-file',true);nextTick(()=>composer.value?.focus());}
    function clearSqlContext(){emit('clear-selection');emit('include-file',false);}
    function resizeComposer(){const node=composer.value;if(!node)return;node.style.height='auto';node.style.height=Math.min(160,Math.max(68,node.scrollHeight))+'px';}
    function inputPrompt(value){emit('prompt',value);resizeComposer();}
    function retryQuestion(turn){const turns=displayMessages.value;for(let i=turns.indexOf(turn)-1;i>=0;i--)if(turns[i].role==='user')return turns[i].text;return '';}
    function canRetry(turn){if(turn.role!=='assistant' || !retryQuestion(turn))return false;
      return isCurrentTurn(turn)?progress.value.terminal && (!['SUCCESS','PARTIAL','PARTIAL_SUCCESS'].includes(progress.value.status) || !!props.result?.outputError):turn.status==='failed';}
    function retry(turn){if(props.busy || props.taskRunning || props.pendingPhase)return;emit('retry',{question:retryQuestion(turn),action:turn.action || props.aiAction});}
    function hasSql(text,source){return sqlAssistantParts(text).some(part=>part.type==='sql' && part.content.trim()===String(source || '').trim());}
    let observer;
    onMounted(()=>{nextTick(resizeComposer);if(typeof ResizeObserver!=='undefined'){let width;observer=new ResizeObserver(entries=>{const next=entries[0]?.contentRect.width;if(width!==next){width=next;resizeComposer();}});if(composer.value)observer.observe(composer.value);}});
    watch(()=>props.prompt,()=>nextTick(resizeComposer));
    const closeDialogs=()=>{closeDialog(settingsDialog.value);closeDialog(historyDialog.value);};
    onDeactivated(closeDialogs);onBeforeUnmount(()=>{observer?.disconnect();closeDialogs();});
    function followupSql(){emit('action','explore');emit('prompt','请基于上述已确认的元数据生成 SQL。统计口径与筛选条件：');nextTick(()=>composer.value?.focus());}
    return { canInsertTurn,settingsDialog,historyDialog,canSend,showDialog,closeDialog,chooseHistory,historyTitle,suggest,clearSqlContext,inputPrompt,canRetry,retry,hasSql,
      sqlAssistantError,followupSql, availableAgents, displayMessages, isCurrentTurn, turnProgressSteps, codePlan, codePlanState, studioAiLabels, resultAction, operationLabel, fileDiff, newConversation, terminal, progress, trackScroll, send, scroll, composer };
  }
};
