import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue';
import {ChevronDown,RefreshCw,Save,PanelLeftOpen,Maximize2,Table2,Info,X,Plus,LoaderCircle,Check,ChevronRight} from '@lucide/vue';
import MarkdownIt from 'markdown-it';
import PythonReportChart from '../../components/PythonReportChart.vue';
import ReportMarkdown from '../../components/ReportMarkdown.vue';
import PromptComposer from '../../components/PromptComposer.vue';
import ExplorationChain from '../../components/ExplorationChain.vue';
import ExplorationDataTable from '../../components/ExplorationDataTable.vue';
import {createExploration,listExplorations,getExploration,prepareExploration,executeExploration,saveExplorationNode,explorationAgents,explorationNodeStatus} from '../../services/explorationApi';
import {workspaceStorageKey} from '../utils/workspaceContext';
import {workspaceBusinessName,businessNodeTitle} from '../utils/explorationWorkspace';
import {explorationFigure} from '../utils/explorationPresentation';
import {splitThinkBlocks} from '../utils/thinkBlocks';

export default {
  name:'DataExplorationView',components:{PythonReportChart,ReportMarkdown,PromptComposer,ExplorationChain,ExplorationDataTable,
    ChevronDown,RefreshCw,Save,PanelLeftOpen,Maximize2,Table2,Info,X,Plus,LoaderCircle,Check,ChevronRight},
  props:{workspaceContext:{type:Object,default:null}},
  setup(props){
    const markdown=new MarkdownIt({html:false,linkify:false,breaks:true});
    const spaces=ref([]),space=ref(null),selected=ref([]),activeId=ref(''),title=ref('客户资产分析'),question=ref('');
    const agents=ref([]),agentsLoading=ref(false),selectedAgentId=ref('');
    const agentAvailable=computed(()=>agents.value.some(a=>a.id===selectedAgentId.value));
    const loading=ref(false),mutationBusy=ref(false),refreshing=ref(false),analyzing=ref(false),saving=ref(false);
    const runningId=ref(''),runningSpaceId=ref(''),error=ref(''),notice=ref('');
    const workspaceElement=ref(null),workspaceDialog=ref(null),chartDialog=ref(null),tableDialog=ref(null),detailsDialog=ref(null);
    const sidebarWidth=ref(260),sidebarCollapsed=ref(false),collapsedBranches=ref([]),resizing=ref(false);
    const chartSelections=ref({}),chartCache=ref({}),tableOpen=ref(false),expandedChart=ref(false);
    let noticeTimer,resizeOrigin,resizeStartWidth,pollTimer,polling=false,disposed=false;
    const preferenceKey=computed(()=>workspaceStorageKey('chatchat.exploration.ui.v1',props.workspaceContext));
    const busy=computed(()=>loading.value||mutationBusy.value||refreshing.value);
    const active=computed(()=>space.value?.nodes.find(n=>n.id===activeId.value));
    const report=computed(()=>active.value?.receipt?.visualReport);
    const authoredReport=computed(()=>splitThinkBlocks(active.value?.receipt?.results?.[0]?.reportMarkdown).filter(b=>b.type==='text').map(b=>b.content).join('\n'));
    const charts=computed(()=>report.value?.charts||[]);
    const currentChart=computed(()=>charts.value.find(c=>c.id===chartSelections.value[activeId.value])||charts.value[0]);
    const chartKey=computed(()=>`${activeId.value}:${currentChart.value?.id||''}`);
    const chartMeta=computed(()=>chartCache.value[chartKey.value]);
    const chartData=computed(()=>{
      if(chartMeta.value?.table)return chartMeta.value.table;
      const section=report.value?.sections?.find(s=>s.chartIds?.includes(currentChart.value?.id));
      return report.value?.tables?.find(t=>section?.tableIds?.includes(t.id))||report.value?.tables?.[0]||null;
    });
    const chartTypes=[{value:'bar',label:'柱状图'},{value:'line',label:'折线图'},{value:'pie',label:'饼图'},{value:'scatter',label:'散点图'}];
    const compatibleTypes=computed(()=>chartMeta.value?.compatibleTypes||[]);
    const effectiveChartType=computed(()=>{
      const saved=active.value?.chartTypes?.[currentChart.value?.id];if(saved)return saved;
      const traces=chartMeta.value?.figure?.data;if(!traces?.length)return '';
      const types=traces.map(t=>t.type==='scatter'||!t.type?(t.mode?.includes('lines')?'line':'scatter'):t.type);
      return new Set(types).size===1&&chartTypes.some(t=>t.value===types[0])?types[0]:'';
    });
    const heading=computed(()=>active.value?businessNodeTitle(active.value):'新的分析');
    const summary=computed(()=>{const text=report.value?.summary||authoredReport.value.replace(/^#{1,6}[^\n]*\n/gm,'').replace(/[*#`]/g,'').trim().split('\n').filter(Boolean).slice(0,2).join(' ');return text.length>190?text.slice(0,189)+'…':text;});
    const hasData=computed(()=>!!(active.value?.fileIds?.length||selected.value.length||space.value?.fileIds?.length));
    const contentStyle=computed(()=>({'--chain-width':sidebarCollapsed.value?'0px':`${sidebarWidth.value}px`}));
    function message(value){notice.value=value;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>notice.value='',4000);}
    function selection(){return new URLSearchParams(window.location.hash.split('?')[1]||'');}
    function remember(){
      if(!space.value)return;const params=selection();params.set('exploration',space.value.id);params.set('node',activeId.value);
      window.history.replaceState(null,'',`${window.location.hash.split('?')[0]||'#/w/data-science/dataExploration'}?${params}`);
    }
    function readPreferences(){
      if(!preferenceKey.value)return false;try{const raw=localStorage.getItem(preferenceKey.value);if(!raw)return false;const saved=JSON.parse(raw);
        if(Number.isFinite(saved.width))sidebarWidth.value=Math.min(420,Math.max(200,saved.width));
        sidebarCollapsed.value=!!saved.collapsed;collapsedBranches.value=Array.isArray(saved.branches)?saved.branches.filter(x=>typeof x==='string').slice(0,100):[];
        chartSelections.value=saved.charts&&typeof saved.charts==='object'?saved.charts:{};
        return true;
      }catch{/* Invalid layout preferences never block analysis restoration. */}
    }
    function writePreferences(){if(!preferenceKey.value)return;try{localStorage.setItem(preferenceKey.value,JSON.stringify({width:sidebarWidth.value,collapsed:sidebarCollapsed.value,branches:collapsedBranches.value,charts:chartSelections.value}));}catch{}}
    watch([sidebarWidth,sidebarCollapsed,collapsedBranches,chartSelections],writePreferences,{deep:true});
    function revealAncestors(node){const nodes=space.value?.nodes||[],parents=new Set();let parent=node?.parentId;while(parent&&!parents.has(parent)){parents.add(parent);parent=nodes.find(n=>n.id===parent)?.parentId;}collapsedBranches.value=collapsedBranches.value.filter(id=>!parents.has(id));}
    function selectNode(node){
      const actual=space.value?.nodes.find(n=>n.id===node.id);if(!actual)return;
      activeId.value=actual.id;selectedAgentId.value=actual.agentId||'';selected.value=[...actual.fileIds];tableOpen.value=false;error.value='';revealAncestors(actual);remember();
    }
    async function refreshList(){spaces.value=await listExplorations();}
    async function open(id){
      const params=selection(),loaded=await getExploration(id);space.value=loaded;
      activeId.value=params.get('exploration')===id&&loaded.nodes.some(n=>n.id===params.get('node'))?params.get('node'):loaded.nodes.at(-1)?.id||'';
      selectedAgentId.value=active.value?.agentId||'';selected.value=[...(active.value?.fileIds||loaded.fileIds||[])];scheduleStatus();tableOpen.value=false;revealAncestors(active.value);remember();
    }
    async function run(action){if(mutationBusy.value)return;mutationBusy.value=true;error.value='';try{await action();}catch(e){error.value=e.message||'暂时无法完成操作';}finally{mutationBusy.value=false;}}
    function closeDialog(dialog){if(dialog===chartDialog.value)expandedChart.value=false;dialog?.close?.();}
    function showDialog(dialog){if(dialog===chartDialog.value)expandedChart.value=true;if(dialog?.showModal&&!dialog.open)dialog.showModal();}
    async function create(){await run(async()=>{
      const source=space.value?{sourceWorkspaceId:space.value.id,...(active.value?{sourceNodeId:active.value.id}:{})}:{};
      const created=await createExploration(title.value,source);space.value=created;activeId.value='';selected.value=[...(created.fileIds||[])];tableOpen.value=false;remember();
      await refreshList();closeDialog(workspaceDialog.value);question.value='';
    });}
    async function save(types=active.value?.chartTypes||{},saved=true,nodeId=activeId.value){
      if(!space.value||!nodeId)return;const id=space.value.id;
      const updated=await saveExplorationNode(id,nodeId,{version:space.value.version,saved,chartTypes:types});
      if(space.value?.id===id)space.value=updated;return updated;
    }
    async function execute(id,node){
      try{const updated=await executeExploration(id,node.id);if(space.value?.id===id)space.value=updated;
        const receipt=updated.nodes.find(n=>n.id===node.id)?.receipt;message(receipt?.resultAvailable?(receipt.visualReport?.charts?.length?'图形分析已完成':'分析结果已返回，暂未生成图表'):receipt?.agentTaskId?'Agent 正在获取数据并分析':'分析尚未完成，可以继续恢复');scheduleStatus();
      }catch(e){error.value=e.message||'分析暂未完成';try{const updated=await getExploration(id);if(space.value?.id===id)space.value=updated;}catch{}}
    }
    async function ask(payload){
      if(analyzing.value||busy.value)return;
      const text=(payload?.query??question.value).trim();if(!text)return;if(text.length>6000){error.value='问题最多6000字';return;}
      if(!agentAvailable.value){error.value='请选择已发布且获授权的 Agent';return;}
      if(!space.value){try{space.value=await createExploration(agents.value.find(a=>a.id===selectedAgentId.value).name+'分析');await refreshList();remember();}catch(e){error.value=e.message;return;}}
      const id=space.value.id,parent=active.value,selectionAtSend=activeId.value;
      if(parent&&!parent.receipt?.resultAvailable){error.value='当前分析尚未完成，请先恢复或选择已完成的分析';return;}
      if(parent&&parent.agentId!==selectedAgentId.value){error.value='切换 Agent 请开始新的分析';return;}
      analyzing.value=true;runningSpaceId.value=id;error.value='';mutationBusy.value=true;
      try{
        if(parent&&!parent.saved)await save(parent.chartTypes||{},true,parent.id);
        const prepared=await prepareExploration(id,{version:space.value.version,parentId:parent?.id||null,agentId:selectedAgentId.value,fileIds:parent?.fileIds||selected.value||space.value.fileIds,question:text,modelName:parent?.modelName||null});
        const node=prepared.nodes.at(-1);runningId.value=node.id;
        if(space.value?.id===id){space.value=prepared;if(activeId.value===selectionAtSend){activeId.value=node.id;tableOpen.value=false;remember();}}
        question.value='';mutationBusy.value=false;await execute(id,node);
      }catch(e){error.value=e.message||'暂时无法开始分析';}
      finally{mutationBusy.value=false;analyzing.value=false;runningId.value='';runningSpaceId.value='';}
    }
    async function recover(){if(analyzing.value||!active.value)return;const id=space.value.id,node=active.value;analyzing.value=true;runningId.value=node.id;runningSpaceId.value=id;error.value='';try{await execute(id,node);}finally{analyzing.value=false;runningId.value='';runningSpaceId.value='';}}
    async function reload(){if(refreshing.value||!space.value)return;refreshing.value=true;error.value='';const id=space.value.id,selectedId=activeId.value;try{const updated=await getExploration(id);if(space.value?.id===id){space.value=updated;if(updated.nodes.some(n=>n.id===selectedId))activeId.value=selectedId;remember();}message('已更新');}catch(e){error.value=e.message;}finally{refreshing.value=false;}}
    async function saveNode(){saving.value=true;await run(async()=>{await save();message('已保存');});saving.value=false;}
    function chartReady(payload){for(const node of space.value?.nodes||[])if(node.receipt?.visualReport?.charts?.some(c=>c.id===payload.id&&c.artifact?.fileId===payload.fileId))chartCache.value[`${node.id}:${payload.id}`]=payload;}
    async function chartType(chart,event){
      const type=event.target.value,meta=chartCache.value[`${activeId.value}:${chart.id}`];
      await run(async()=>{if(type){if(!meta)throw new Error('图表正在加载，请稍后再试');explorationFigure(meta.figure,type);}
        const types={...active.value.chartTypes};if(type)types[chart.id]=type;else delete types[chart.id];await save(types,active.value.saved);message('图表已更新');});
      event.target.value=effectiveChartType.value;
    }
    function toggleBranch(id){collapsedBranches.value=collapsedBranches.value.includes(id)?collapsedBranches.value.filter(x=>x!==id):[...collapsedBranches.value,id];}
    function selectChart(id){chartSelections.value={...chartSelections.value,[activeId.value]:id};tableOpen.value=false;}
    function selectAgent(id){if(analyzing.value)return;if(active.value&&active.value.agentId!==id)startRoot();selectedAgentId.value=id;error.value='';}
    function startRoot(){selected.value=[...(active.value?.fileIds||space.value?.fileIds||selected.value)];activeId.value='';tableOpen.value=false;question.value='';remember();}
    function moveResize(event){const available=workspaceElement.value?.clientWidth||1200;sidebarWidth.value=Math.round(Math.max(200,Math.min(420,available*.38,resizeStartWidth+event.clientX-resizeOrigin)));}
    function stopResize(){resizing.value=false;window.removeEventListener('pointermove',moveResize);window.removeEventListener('pointerup',stopResize);}
    function startResize(event){event.preventDefault();resizeOrigin=event.clientX;resizeStartWidth=sidebarWidth.value;resizing.value=true;window.addEventListener('pointermove',moveResize);window.addEventListener('pointerup',stopResize);}
    function scheduleStatus(){clearTimeout(pollTimer);if(!disposed)pollTimer=setTimeout(pollStatus,1500);}
    async function pollStatus(){
      if(disposed||polling)return;const id=space.value?.id;
      const pending=space.value?.nodes.find(n=>n.agentId&&n.receipt?.agentTaskId&&!n.receipt.resultAvailable&&!n.receipt.terminal);
      if(!pending)return;polling=true;
      try{const updated=await explorationNodeStatus(id,pending.id);if(!disposed&&space.value?.id===id)space.value=updated;}catch(e){if(!disposed)error.value=e.message;}finally{polling=false;scheduleStatus();}
    }
    const taskRunning=computed(()=>analyzing.value||!!space.value?.nodes.some(n=>n.agentId&&n.receipt?.agentTaskId&&!n.receipt.resultAvailable&&!n.receipt.terminal));
    onMounted(async()=>{loading.value=true;agentsLoading.value=true;
      try{agents.value=await explorationAgents();}catch(e){error.value=e.message;}finally{agentsLoading.value=false;}const restored=readPreferences(),width=workspaceElement.value?.clientWidth;if(!restored&&width>0&&width<850)sidebarCollapsed.value=true;
      try{await refreshList();if(spaces.value.length)await open(spaces.value.find(s=>s.id===selection().get('exploration'))?.id||spaces.value[0].id);}catch(e){error.value=e.message;}finally{loading.value=false;}});
    onBeforeUnmount(()=>{disposed=true;stopResize();clearTimeout(noticeTimer);clearTimeout(pollTimer);});
    return {agents,agentsLoading,selectedAgentId,agentAvailable,selectAgent,taskRunning,spaces,space,selected,activeId,title,question,active,report,authoredReport,charts,currentChart,chartKey,chartData,chartTypes,compatibleTypes,effectiveChartType,heading,summary,
      loading,busy,refreshing,analyzing,saving,runningId,runningSpaceId,error,notice,hasData,sidebarWidth,sidebarCollapsed,collapsedBranches,resizing,contentStyle,
      workspaceElement,workspaceDialog,chartDialog,tableDialog,detailsDialog,tableOpen,expandedChart,workspaceBusinessName,businessNodeTitle,
      open,run,create,ask,recover,reload,saveNode,save,selectNode,chartType,chartReady,selectChart,startRoot,toggleBranch,startResize,
      resizeBy:amount=>sidebarWidth.value=Math.max(200,Math.min(420,sidebarWidth.value+amount)),
      switchSpace:id=>run(async()=>{await open(id);closeDialog(workspaceDialog.value);}),
      showDialog,closeDialog,renderMarkdown:text=>markdown.render(text),
      showRunning:()=>{if(space.value?.id===runningSpaceId.value){const node=space.value.nodes.find(n=>n.id===runningId.value);if(node)selectNode(node);}}};
  }
};
