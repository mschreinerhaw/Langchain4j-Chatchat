import {reportExcerpt} from '../utils/reportExcerpt';
import {computed,onMounted,onBeforeUnmount,ref,watch} from 'vue';
import {ChevronDown,RefreshCw,Save,PanelLeftOpen,Maximize2,Table2,Info,X,Plus,LoaderCircle,Check,ChevronRight,Ellipsis,Pencil,Trash2,Square} from '@lucide/vue';
import MarkdownIt from 'markdown-it';
import PythonReportChart from '../../components/PythonReportChart.vue';
import ReportMarkdown from '../../components/ReportMarkdown.vue';
import PromptComposer from '../../components/PromptComposer.vue';
import ExplorationChain from '../../components/ExplorationChain.vue';
import ExplorationDataTable from '../../components/ExplorationDataTable.vue';
import ExplorationActivityPanel from '../../components/ExplorationActivityPanel.vue';
import AppConfirmDialog from '../../components/AppConfirmDialog.vue';
import {createExploration,listExplorations,getExploration,renameExploration,deleteExploration,prepareExploration,executeExploration,stopExploration,saveExplorationNode,explorationAgents,explorationNodeStatus,generateExplorationIdeas,uploadExplorationAttachment} from '../../services/explorationApi';
import {validateChatFiles,chatTextFile,exceedsChatTextLimit,CHAT_FILE_ANALYSIS_OBJECTIVE} from '../utils/chatUserAssets';
import {workspaceStorageKey} from '../utils/workspaceContext';
import {workspaceBusinessName,businessNodeTitle,explorationNodeState,explorationCanvasNode,reconcileExploration,explorationMetricDisplay} from '../utils/explorationWorkspace';
import {explorationFigure} from '../utils/explorationPresentation';
import {splitThinkBlocks} from '../utils/thinkBlocks';
import {reportPresentation} from '../utils/reportLayout';
import {chartDisplayTitle} from '../utils/chartDisplayTitle';

export default {
  name:'DataExplorationView',components:{PythonReportChart,ReportMarkdown,PromptComposer,ExplorationChain,ExplorationDataTable,ExplorationActivityPanel,AppConfirmDialog,
    ChevronDown,RefreshCw,Save,PanelLeftOpen,Maximize2,Table2,Info,X,Plus,LoaderCircle,Check,ChevronRight,Ellipsis,Pencil,Trash2,Square},
  props:{workspaceContext:{type:Object,default:null}},
  setup(props){
    const markdown=new MarkdownIt({html:false,linkify:false,breaks:true});
    const spaces=ref([]),space=ref(null),selected=ref([]),activeId=ref(''),title=ref('客户资产分析'),question=ref('');
    const agents=ref([]),agentsLoading=ref(false),selectedAgentId=ref('');
    const projectMenu=ref(''),projectAction=ref(null),projectName=ref(''),projectActionBusy=ref(false),projectActionError=ref('');
    const attachments=ref([]);
    async function uploadAttachment(item){item.state='uploading';item.error='';try{item.asset=await uploadExplorationAttachment(item.file,item.workspaceContext);item.state='ready';}catch(e){item.state='failed';item.error=e.message||'上传失败，可重试';}}
    async function addFiles(files){
      if(analyzing.value||busy.value)return false;
      try{validateChatFiles(files,attachments.value.map(f=>f.file));const items=files.map((file,index)=>({clientId:`attachment-${Date.now()}-${index}-${Math.random().toString(36).slice(2)}`,name:file.name,file,state:'uploading',asset:null,error:'',workspaceContext:props.workspaceContext?{...props.workspaceContext}:null}));
        attachments.value.push(...items);const added=attachments.value.filter(f=>items.some(i=>i.clientId===f.clientId));await Promise.all(added.map(uploadAttachment));return added;
      }catch(e){error.value=e.message;return false;}
    }
    async function retryFile(id){const item=attachments.value.find(f=>f.clientId===id);if(item&&!analyzing.value&&!busy.value)await uploadAttachment(item);}
    function removeFile(id){if(!analyzing.value&&!busy.value)attachments.value=attachments.value.filter(f=>f.clientId!==id);}
    async function suggestIdeas({draft,agentId,signal}){
      if(attachments.value.some(f=>f.state!=='ready'))throw new Error('请先完成附件上传，或移除失败的附件。');
      return generateExplorationIdeas({draft,agentId,workspaceId:space.value?.id,nodeId:activeId.value||undefined,fileIds:attachments.value.map(f=>f.asset.id)},{signal,workspaceContext:props.workspaceContext});
    }
    const agentAvailable=computed(()=>agents.value.some(a=>a.id===selectedAgentId.value));
    const loading=ref(false),mutationBusy=ref(false),refreshing=ref(false),analyzing=ref(false),saving=ref(false);
    const runningId=ref(''),runningSpaceId=ref(''),error=ref(''),notice=ref('');
    const stoppingId=ref('');
    const workspaceElement=ref(null),workspaceDialog=ref(null),chartDialog=ref(null),tableDialog=ref(null),detailsDialog=ref(null);
    const sidebarWidth=ref(260),sidebarCollapsed=ref(false),collapsedBranches=ref([]),resizing=ref(false);
    const chartSelections=ref({}),chartCache=ref({}),tableOpen=ref(false),expandedChart=ref(false);
    let noticeTimer,resizeOrigin,resizeStartWidth,pollTimer,polling=false,disposed=false,narrowViewport=false,lastPolledNode='',analysisSerial=0;
    function syncViewport(){const narrow=window.innerWidth<=980;if(narrow&&!narrowViewport)sidebarCollapsed.value=true;narrowViewport=narrow;}
    const preferenceKey=computed(()=>workspaceStorageKey('chatchat.exploration.ui.v1',props.workspaceContext));
    const busy=computed(()=>loading.value||mutationBusy.value||refreshing.value);
    const active=computed(()=>space.value?.nodes.find(n=>n.id===activeId.value));
    const canvasNode=computed(()=>explorationCanvasNode(space.value?.nodes||[],activeId.value));
    const selectedState=computed(()=>explorationNodeState(active.value,runningSpaceId.value===space.value?.id?runningId.value:''));
    const canContinueLegacy=computed(()=>!!active.value && !active.value.agentId && !!active.value.fileIds?.length && selectedState.value.code==='COMPLETED');
    const canvasState=computed(()=>explorationNodeState(canvasNode.value));
    const showingPrevious=computed(()=>!!canvasNode.value&&canvasNode.value.id!==activeId.value);
    const report=computed(()=>canvasNode.value?.receipt?.visualReport);
    const metrics=computed(()=>report.value?.metrics||[]);
    function applySpace(value){space.value=reconcileExploration(space.value,value);}

    const authoredReport=computed(()=>splitThinkBlocks(canvasNode.value?.receipt?.results?.[0]?.reportMarkdown).filter(b=>b.type==='text').map(b=>b.content).join('\n'));
    const charts=computed(()=>report.value?.charts||[]);
    const currentChart=computed(()=>charts.value.find(c=>c.id===chartSelections.value[canvasNode.value?.id])||charts.value[0]);
    const chartKey=computed(()=>`${canvasNode.value?.id}:${currentChart.value?.id||''}`);
    const chartMeta=computed(()=>chartCache.value[chartKey.value]);
    const chartData=computed(()=>{
      if(chartMeta.value?.table)return chartMeta.value.table;
      const section=report.value?.sections?.find(s=>s.chartIds?.includes(currentChart.value?.id));
      return report.value?.tables?.find(t=>section?.tableIds?.includes(t.id))||report.value?.tables?.[0]||null;
    });
    const chartTypes=[{value:'bar',label:'柱状图'},{value:'line',label:'折线图'},{value:'pie',label:'饼图'},{value:'donut',label:'环形图'},{value:'scatter',label:'散点图'}];
    const compatibleTypes=computed(()=>chartMeta.value?.compatibleTypes||[]);
    const effectiveChartType=computed(()=>{
      const saved=canvasNode.value?.chartTypes?.[currentChart.value?.id];if(saved)return saved;
      const traces=chartMeta.value?.figure?.data;if(!traces?.length)return '';
      const types=traces.map(t=>t.type==='pie'&&t.hole>0?'donut':t.type==='scatter'||!t.type?(t.mode?.includes('lines')?'line':'scatter'):t.type);
      return new Set(types).size===1&&chartTypes.some(t=>t.value===types[0])?types[0]:'';
    });
    const heading=computed(()=>canvasNode.value?businessNodeTitle(canvasNode.value):active.value?businessNodeTitle(active.value):'新的分析');
    const ideaContext=computed(()=>({hasResult:!!active.value?.receipt?.resultAvailable,title:heading.value}));
    const summary=computed(()=>reportExcerpt(report.value?.summary,authoredReport.value));
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
      attachments.value=[];activeId.value=actual.id;selectedAgentId.value=actual.agentId||'';selected.value=[...actual.fileIds];tableOpen.value=false;error.value='';revealAncestors(actual);remember();
      if(narrowViewport)sidebarCollapsed.value=true;
    }
    async function refreshList(){spaces.value=await listExplorations();}
    async function open(id){
      const params=selection(),loaded=await getExploration(id);attachments.value=[];space.value=loaded;
      activeId.value=params.get('exploration')===id&&loaded.nodes.some(n=>n.id===params.get('node'))?params.get('node'):loaded.nodes.at(-1)?.id||'';
      selectedAgentId.value=active.value?.agentId||'';selected.value=[...(active.value?.fileIds||loaded.fileIds||[])];scheduleStatus();tableOpen.value=false;revealAncestors(active.value);remember();
    }
    async function run(action){if(mutationBusy.value)return;mutationBusy.value=true;error.value='';try{await action();}catch(e){error.value=e.message||'暂时无法完成操作';}finally{mutationBusy.value=false;}}
    function closeDialog(dialog){if(dialog===chartDialog.value)expandedChart.value=false;dialog?.close?.();}
    function showDialog(dialog){if(dialog===chartDialog.value)expandedChart.value=true;if(dialog?.showModal&&!dialog.open)dialog.showModal();}
    async function create(){await run(async()=>{
      const source=space.value?{sourceWorkspaceId:space.value.id,...(active.value?{sourceNodeId:active.value.id}:{})}:{};
      const created=await createExploration(title.value,source);space.value=created;activeId.value='';selected.value=[...(created.fileIds||[])];tableOpen.value=false;remember();
      await refreshList();closeDialog(workspaceDialog.value);question.value='';attachments.value=[];
    });}
    async function manageProject(item,kind){
      if(busy.value||projectAction.value)return;projectMenu.value='';
      await run(async()=>{const state=await getExploration(item.id);projectName.value=state.title;projectActionError.value='';projectAction.value={kind,state};});
    }
    function cancelProjectAction(){if(!projectActionBusy.value)projectAction.value=null;}
    async function submitProjectAction(){
      if(!projectAction.value||projectActionBusy.value)return;
      const {kind,state}=projectAction.value,name=projectName.value.trim();
      if(kind==='rename'&&(!name||name.length>120)){projectActionError.value='请输入 1–120 字的分析项目名称';return;}
      projectActionBusy.value=true;projectActionError.value='';
      try{
        if(kind==='rename'){
          const updated=await renameExploration(state.id,{version:state.version,title:name});
          if(space.value?.id===state.id)applySpace(updated);
          spaces.value=spaces.value.map(item=>item.id===state.id?{...item,title:updated.title,version:updated.version}:item);
          message('分析项目名称已修改');
        }else{
          await deleteExploration(state.id,state.version);
          spaces.value=spaces.value.filter(item=>item.id!==state.id);
          if(space.value?.id===state.id){
            clearTimeout(pollTimer);space.value=null;activeId.value='';selected.value=[];selectedAgentId.value='';question.value='';attachments.value=[];tableOpen.value=false;chartCache.value={};
            const params=selection();params.delete('exploration');params.delete('node');
            window.history.replaceState(null,'',window.location.hash.split('?')[0]+(params.size?'?'+params:''));
            if(spaces.value.length){try{await open(spaces.value[0].id);}catch(e){error.value=e.message||'项目已删除，请重新选择分析项目';}}
          }
          message('分析项目已删除');
        }
        projectAction.value=null;
      }catch(e){projectActionError.value=e.message||'暂时无法完成操作，请重试';}
      finally{projectActionBusy.value=false;}
    }
    async function save(types=active.value?.chartTypes||{},saved=true,nodeId=activeId.value){
      if(!space.value||!nodeId)return;const id=space.value.id;
      const updated=await saveExplorationNode(id,nodeId,{version:space.value.version,saved,chartTypes:types});
      if(space.value?.id===id)applySpace(updated);return updated;
    }
    const stopTarget=computed(()=>{
      const nodes=space.value?.nodes||[];
      const eligible=node=>!!node&&node.receipt?.terminal!==true&&!['CANCELLED','COMPLETED','FAILED'].includes(explorationNodeState(node).code)&&
        (node.agentId?(node.receipt?.agentTaskId||node.id===runningId.value&&runningSpaceId.value===space.value?.id):node.receipt?.taskId);
      return eligible(active.value)?active.value:nodes.find(node=>eligible(node)&&node.id===runningId.value)||nodes.find(eligible);
    });
    const stopping=computed(()=>!!stoppingId.value||!!stopTarget.value?.receipt?.cancellationRequested);
    async function stopAnalysis(){
      const target=stopTarget.value,id=space.value?.id;
      if(!target||stopping.value)return;
      stoppingId.value=target.id;error.value='';
      try{
        const updated=await stopExploration(id,target.id,props.workspaceContext);
        if(!disposed&&space.value?.id===id){
          applySpace(updated);
          const stopped=updated.nodes.find(node=>node.id===target.id);
          if(['CANCELLED','COMPLETED','FAILED'].includes(explorationNodeState(stopped).code)&&runningSpaceId.value===id&&runningId.value===target.id){
            analysisSerial++;analyzing.value=false;runningId.value='';runningSpaceId.value='';
          }
          message(explorationNodeState(stopped).code==='CANCELLED'?'分析已停止，已有成果已保留':stopped?.receipt?.terminal?'分析已经结束':'已请求停止，正在等待执行状态确认');
          scheduleStatus();
        }
      }catch(e){if(!disposed&&space.value?.id===id)error.value=e.message||'停止请求未确认，请重试';}
      finally{if(stoppingId.value===target.id)stoppingId.value='';}
    }
    async function execute(id,node){
      try{const updated=await executeExploration(id,node.id);if(space.value?.id===id)applySpace(updated);
        if(space.value?.id!==id)return;
        const current=space.value.nodes.find(n=>n.id===node.id),receipt=current?.receipt;
        message(explorationNodeState(current).code==='CANCELLED'?'分析已停止，已有成果已保留':receipt?.resultAvailable?(receipt.visualReport?.charts?.length?'图形分析已完成':'分析结果已返回，暂未生成图表'):receipt?.agentTaskId?'Agent 正在获取数据并分析':'分析尚未完成，可以继续恢复');scheduleStatus();
      }catch(e){if(space.value?.id!==id||space.value?.nodes.find(n=>n.id===node.id)?.receipt?.terminal)return;error.value=e.message||'分析暂未完成';try{const updated=await getExploration(id);if(space.value?.id===id)applySpace(updated);}catch{}}
    }
    async function ask(payload){
      if(analyzing.value||busy.value||['RUNNING','STOPPING'].includes(selectedState.value.code))return;
      const original=payload?.query??question.value,originSpace=space.value?.id,originNode=activeId.value,originAgent=selectedAgentId.value;
      let text=original.trim();if(!text&&!attachments.value.length)return;
      if(!agentAvailable.value&&!canContinueLegacy.value){error.value='请选择已发布且获授权的 Agent';return;}
      if(exceedsChatTextLimit(original)||original.length>8000){
        let item=attachments.value.find(f=>f.originalText===original);
        if(!item){const added=await addFiles([chatTextFile(original)]);if(!added)return;item=added[0];item.originalText=original;}
        text=CHAT_FILE_ANALYSIS_OBJECTIVE;
      }
      if(disposed||space.value?.id!==originSpace||activeId.value!==originNode||selectedAgentId.value!==originAgent)return;
      if(attachments.value.some(f=>f.state!=='ready')){error.value='请先重试或移除上传失败的附件';return;}
      if(!text)text='请结合补充资料与当前分析，核对数据、说明主要变化和需要进一步确认的问题。';
      const fileIds=[...new Set([...(active.value?.fileIds||selected.value||space.value?.fileIds||[]),...attachments.value.map(f=>f.asset.id)])];
      if(fileIds.length>8){error.value='当前节点与补充资料合计最多 8 个附件，请开始新的分析以更换数据';return;}
      if(!space.value){try{space.value=await createExploration(agents.value.find(a=>a.id===selectedAgentId.value).name+'分析');await refreshList();remember();}catch(e){error.value=e.message;return;}}
      const id=space.value.id,parent=active.value?.receipt?.resultAvailable && selectedState.value.code==='COMPLETED'?active.value:null,selectionAtSend=activeId.value;
      if(parent&&(parent.agentId||'')!==selectedAgentId.value){error.value='切换 Agent 请开始新的分析';return;}
      analyzing.value=true;runningSpaceId.value=id;error.value='';mutationBusy.value=true;
      const serial=++analysisSerial;
      try{
        if(parent&&!parent.saved)await save(parent.chartTypes||{},true,parent.id);
        const prepared=await prepareExploration(id,{version:space.value.version,parentId:parent?.id||null,agentId:selectedAgentId.value,fileIds,question:text,modelName:parent?.modelName||null,reportPresentation:reportPresentation(payload?.reportPresentation||parent?.reportPresentation||{})});
        const node=prepared.nodes.at(-1);runningId.value=node.id;
        if(space.value?.id===id){applySpace(prepared);if(activeId.value===selectionAtSend){activeId.value=node.id;tableOpen.value=false;remember();}}
        question.value='';attachments.value=[];mutationBusy.value=false;await execute(id,node);
      }catch(e){if(serial===analysisSerial)error.value=e.message||'暂时无法开始分析';}
      finally{if(serial===analysisSerial){mutationBusy.value=false;analyzing.value=false;runningId.value='';runningSpaceId.value='';}}
    }
    async function recover(){if(analyzing.value||!active.value||active.value.receipt?.terminal)return;const id=space.value.id,node=active.value,serial=++analysisSerial;analyzing.value=true;runningId.value=node.id;runningSpaceId.value=id;error.value='';try{await execute(id,node);}finally{if(serial===analysisSerial){analyzing.value=false;runningId.value='';runningSpaceId.value='';}}}
    async function reload(){if(refreshing.value||!space.value)return;refreshing.value=true;error.value='';const id=space.value.id,selectedId=activeId.value;try{const updated=await getExploration(id);if(space.value?.id===id){applySpace(updated);if(updated.nodes.some(n=>n.id===selectedId))activeId.value=selectedId;remember();}message('已更新');}catch(e){error.value=e.message;}finally{refreshing.value=false;}}
    async function saveNode(){if(!canvasNode.value)return;const node=canvasNode.value;saving.value=true;await run(async()=>{await save(node.chartTypes||{},true,node.id);message('已保存');});saving.value=false;}
    function chartReady(payload){for(const node of space.value?.nodes||[])if(node.receipt?.visualReport?.charts?.some(c=>c.id===payload.id&&c.artifact?.fileId===payload.fileId))chartCache.value[`${node.id}:${payload.id}`]=payload;}
    async function chartType(chart,event){
      const node=canvasNode.value;if(!node)return;const type=event.target.value,meta=chartCache.value[`${node.id}:${chart.id}`];
      await run(async()=>{if(type){if(!meta)throw new Error('图表正在加载，请稍后再试');explorationFigure(meta.figure,type);}
        const types={...node.chartTypes};if(type)types[chart.id]=type;else delete types[chart.id];await save(types,node.saved,node.id);message('图表已更新');});
      event.target.value=effectiveChartType.value;
    }
    function toggleBranch(id){collapsedBranches.value=collapsedBranches.value.includes(id)?collapsedBranches.value.filter(x=>x!==id):[...collapsedBranches.value,id];}
    function selectChart(id){chartSelections.value={...chartSelections.value,[canvasNode.value?.id]:id};tableOpen.value=false;}
    function chartSelection(point){
      if(!canvasNode.value)return;
      const label=point.label??point.x;if(label==null||!['string','number'].includes(typeof label))return;
      const title=chartDisplayTitle(currentChart.value?.title);selectNode(canvasNode.value);
      question.value=`基于“${title}”，进一步分析当前选中项“${String(label).slice(0,160)}”。请沿用已授权数据，说明口径与数据范围。`;
      workspaceElement.value?.querySelector('textarea')?.focus();
    }
    function selectAgent(id){if(analyzing.value)return;const draft=question.value;if(active.value&&active.value.agentId!==id){startRoot();question.value=draft;}selectedAgentId.value=id;error.value='';}
    function startRoot(){selected.value=[...(active.value?.fileIds||space.value?.fileIds||selected.value)];activeId.value='';tableOpen.value=false;question.value='';remember();}
    function moveResize(event){const available=workspaceElement.value?.clientWidth||1200;sidebarWidth.value=Math.round(Math.max(200,Math.min(420,available*.38,resizeStartWidth+event.clientX-resizeOrigin)));}
    function stopResize(){resizing.value=false;window.removeEventListener('pointermove',moveResize);window.removeEventListener('pointerup',stopResize);}
    function startResize(event){event.preventDefault();resizeOrigin=event.clientX;resizeStartWidth=sidebarWidth.value;resizing.value=true;window.addEventListener('pointermove',moveResize);window.addEventListener('pointerup',stopResize);}
    function scheduleStatus(){clearTimeout(pollTimer);if(!disposed)pollTimer=setTimeout(pollStatus,1500);}
    async function pollStatus(){
      if(disposed||polling)return;const id=space.value?.id;
      const candidates=space.value?.nodes.filter(n=>n.agentId?n.receipt?.agentTaskId&&!n.receipt.terminal:
        (!n.receipt?.terminal||n.receipt?.taskId&&(!n.receipt.resultAvailable&&!n.receipt.stageSnapshotChecked||!n.receipt.progressSnapshotChecked))
        &&(n.executionRequestedAt||n.receipt?.taskId||n.status==='RECOVERABLE'))||[];
      const pending=candidates[(candidates.findIndex(n=>n.id===lastPolledNode)+1)%candidates.length];
      if(!pending)return;polling=true;
      lastPolledNode=pending.id;
      try{const updated=await explorationNodeStatus(id,pending.id);if(!disposed&&space.value?.id===id)applySpace(updated);}catch(e){if(!disposed)error.value=e.message;}finally{polling=false;scheduleStatus();}
    }
    const taskRunning=computed(()=>analyzing.value||!!space.value?.nodes.some(n=>(n.receipt?.agentTaskId||n.receipt?.taskId)&&['RUNNING','STOPPING'].includes(explorationNodeState(n).code)));
    onMounted(async()=>{loading.value=true;agentsLoading.value=true;
      try{agents.value=await explorationAgents();}catch(e){error.value=e.message;}finally{agentsLoading.value=false;}const restored=readPreferences(),width=workspaceElement.value?.clientWidth;if(!restored&&width>0&&width<850)sidebarCollapsed.value=true;
      syncViewport();window.addEventListener('resize',syncViewport);
      try{await refreshList();if(spaces.value.length)await open(spaces.value.find(s=>s.id===selection().get('exploration'))?.id||spaces.value[0].id);}catch(e){error.value=e.message;}finally{loading.value=false;}});
    onBeforeUnmount(()=>{disposed=true;stopResize();clearTimeout(noticeTimer);clearTimeout(pollTimer);window.removeEventListener('resize',syncViewport);});
    return {canvasNode,canvasState,selectedState,showingPrevious,metrics,explorationNodeState,explorationMetricDisplay,chartSelection,canContinueLegacy,
      continueFromCanvas:()=>{if(canvasNode.value){selectNode(canvasNode.value);question.value='';workspaceElement.value?.querySelector('textarea')?.focus();}},
      stopTarget,stopping,stopAnalysis,projectMenu,projectAction,projectName,projectActionBusy,projectActionError,manageProject,cancelProjectAction,submitProjectAction,attachments,addFiles,retryFile,removeFile,suggestIdeas,agents,agentsLoading,selectedAgentId,agentAvailable,selectAgent,taskRunning,spaces,space,selected,activeId,title,question,active,report,authoredReport,charts,currentChart,chartKey,chartData,chartTypes,compatibleTypes,effectiveChartType,heading,summary,
      loading,busy,refreshing,analyzing,saving,runningId,runningSpaceId,error,notice,hasData,sidebarWidth,sidebarCollapsed,collapsedBranches,resizing,contentStyle,
      workspaceElement,workspaceDialog,chartDialog,tableDialog,detailsDialog,tableOpen,expandedChart,workspaceBusinessName,businessNodeTitle,
      open,run,create,ask,recover,reload,saveNode,save,selectNode,chartType,chartReady,selectChart,startRoot,toggleBranch,startResize,
      resizeBy:amount=>sidebarWidth.value=Math.max(200,Math.min(420,sidebarWidth.value+amount)),
      switchSpace:id=>run(async()=>{await open(id);closeDialog(workspaceDialog.value);}),
      showDialog,closeDialog,chartDisplayTitle,ideaContext,renderMarkdown:text=>markdown.render(text),
      showRunning:()=>{if(space.value?.id===runningSpaceId.value){const node=space.value.nodes.find(n=>n.id===runningId.value);if(node)selectNode(node);}}};
  }
};
