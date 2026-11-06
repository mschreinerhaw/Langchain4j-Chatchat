import {computed,ref,watch,nextTick,onMounted,onBeforeUnmount} from 'vue';
import {Activity,ChevronDown,ChevronRight,ArrowDown,LoaderCircle,CircleCheck,CircleAlert,Circle} from '@lucide/vue';
import {executionWorkbench,executionDuration} from '../utils/runtimeObservationPresentation';
import {explorationDeliveryStages,explorationLifecycleStatus} from '../utils/explorationWorkspace';
export default {name:'ExplorationActivityPanel',components:{Activity,ChevronDown,ChevronRight,ArrowDown,LoaderCircle,CircleCheck,CircleAlert,Circle},props:{events:{type:Array,default:()=>[]},receipt:{type:Object,default:()=>({})},running:Boolean,status:{type:String,default:''},startedAt:String,finishedAt:String},setup(props){
const expanded=ref(props.running),tab=ref('activity'),onlyIssues=ref(false),following=ref(true),scroller=ref(null),now=ref(Date.now());
let timer;
watch(()=>props.running,value=>{clearInterval(timer);now.value=Date.now();if(value)timer=setInterval(()=>now.value=Date.now(),1000);},{immediate:true});
onBeforeUnmount(()=>clearInterval(timer));
onMounted(()=>void followLatest());
const workbench=computed(()=>executionWorkbench(props.events,{canonicalState:props.status||(props.running?'RUNNING':'COMPLETED'),createTime:props.startedAt,finishedAt:props.finishedAt},now.value));
const rows=computed(()=>workbench.value.visibleActivities);
const deliveryStages=computed(()=>explorationDeliveryStages(props.events,props.receipt));
const lifecycle=computed(()=>explorationLifecycleStatus(props.events,props.receipt));
const visibleRows=computed(()=>onlyIssues.value?rows.value.filter(row=>row.critical):rows.value);
const currentAction=computed(()=>props.events.length?workbench.value.currentActivity:props.running?'等待执行事件':'查看本次分析记录');
function duration(row){return props.running&&['active','waiting'].includes(row.state)&&row.observedAt
  ?executionDuration(now.value-row.observedAt):row.durationLabel;}
function onScroll(){const el=scroller.value;if(el)following.value=el.scrollHeight-el.scrollTop-el.clientHeight<32;}
async function followLatest(){following.value=true;await nextTick();if(scroller.value)scroller.value.scrollTop=scroller.value.scrollHeight;}
watch(()=>visibleRows.value.map(row=>`${row.id}:${row.sequence}:${row.state}:${row.count}`).join('|'),()=>{if(following.value)void followLatest();});
watch([expanded,tab],()=>{if(following.value)void followLatest();});
function technicalType(event){let value=event.payload||{};try{if(typeof value==='string')value=JSON.parse(value);}catch{return event.type||'EVENT';}
  const meta=(value.payload||value).metadata||{};return meta.workerActivity?.type||meta.codingEventType||event.type||'EVENT';}
function actionTitle(row){
  const event=row.sourceEvents.at(-1);let payload=event?.payload||{};
  try{if(typeof payload==='string')payload=JSON.parse(payload);}catch{return row.title;}
  const meta=(payload.payload||payload).metadata||{},tool=(meta.workerActivity||meta.payload)?.tool;
  const label={market_search:'检索行情数据',market_get_history:'读取历史行情',market_get_snapshot:'读取行情快照',market_get_indicators:'读取金融指标',market_resolve_security:'匹配证券身份',market_get_day_snapshot:'交付当日行情文件',
    news_search:'检索财经资讯',news_get_article:'阅读资讯原文',news_related_events:'检索关联资讯',web_search:'联网检索',web_fetch:'读取网页原文',
    knowledge_search:'检索授权文档',knowledge_get_document:'读取文档信息',knowledge_read:'阅读文档内容',knowledge_list_sections:'读取文档目录',knowledge_related:'查找关联文档'}[tool];
  if(!label)return row.title;
  return row.state==='done'?label+'已完成':['active','waiting'].includes(row.state)?'正在'+label:row.critical?label+'未完成':label+'状态待确认';
}
return {expanded,tab,onlyIssues,following,scroller,workbench,rows,deliveryStages,lifecycle,visibleRows,currentAction,duration,onScroll,followLatest,technicalType,actionTitle};
}};
