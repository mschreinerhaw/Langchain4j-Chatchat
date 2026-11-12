// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';
import {it,expect,vi,afterEach} from 'vitest';
const api=vi.hoisted(()=>Object.fromEntries(['createExploration','listExplorations','getExploration','renameExploration','deleteExploration','prepareExploration','executeExploration','stopExploration','saveExplorationNode','explorationDatasets','explorationAgents','explorationNodeStatus','generateExplorationIdeas','uploadExplorationAttachment'].map(k=>[k,vi.fn()])));
vi.mock('../../services/explorationApi',()=>api);
vi.mock('../../components/PythonReportChart.vue',()=>({default:{props:['chart','presentation'],render(){return h('div',{'data-chart-type':this.presentation},this.chart.title);}}}));
import View from '../../views/DataExplorationView.vue';
vi.mock('../../components/HtmlReportFrame.vue',()=>({default:{props:['report'],render(){return h('div',{'data-html-entry':this.report.entryFileId});}}}));
let app,host;const settle=async()=>{for(let i=0;i<15;i++)await nextTick();};
it('shows delivered HTML directly instead of rebuilding its charts and conclusion',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt.visualReport.htmlReport={entryFileId:'delivered-html',entryPath:'output/report.html',taskId:'task',runId:'run'};
 await mount(state);expect(host.querySelector('[data-html-entry="delivered-html"]')).not.toBeNull();expect(host.querySelector('.exploration-conclusion')).toBeNull();
});
it('shows delivery facts for a restored completed file task without a retained activity stream',async()=>{
 const state={...structuredClone(space),nodes:[{...node('root'),agentId:undefined,receipt:{taskId:'coding-task',status:'COMPLETED',terminal:true,resultAvailable:true,
  visualReport:{status:'SUCCESS',title:'Archived report',summary:'Verified report',charts:[],execution:{operationId:'own',executorStopped:true,inputSnapshotRef:'snapshot'}}}}]};
 await mount(state);expect(host.querySelector('[aria-label="实时分析活动"]')).toBeNull();


 expect(api.executeExploration).not.toHaveBeenCalled();
});
it('hydrates a failed files own archived stage once and keeps the failure visible',async()=>{
 vi.useFakeTimers();try{
 const state={...structuredClone(space),nodes:[{...node('root'),agentId:undefined,receipt:{taskId:'coding-task',status:'FAILED',terminal:true,resultAvailable:false}}]};
 const restored=structuredClone(state);restored.version=4;restored.nodes[0].receipt={...state.nodes[0].receipt,resultAvailable:true,stageSnapshotChecked:true,progressSnapshotChecked:true,deliveryStatus:'PRESERVED_AFTER_FAILURE',
  visualReport:{status:'PARTIAL',title:'Own stage',summary:'Partial archived result',charts:[]}};
 api.explorationNodeStatus.mockResolvedValue(restored);const vm=await mount(state);
 await vi.advanceTimersByTimeAsync(1501);await settle();expect(vm.selectedState.code).toBe('FAILED');expect(vm.report.title).toBe('Own stage');expect(host.textContent).toContain('部分交付');
 await vi.advanceTimersByTimeAsync(1501);await settle();expect(api.explorationNodeStatus).toHaveBeenCalledTimes(1);expect(api.executeExploration).not.toHaveBeenCalled();
 }finally{vi.useRealTimers();}
});
it('reads restored terminal file progress once without executing or continuously polling',async()=>{
 vi.useFakeTimers();try{
 const state={...structuredClone(space),nodes:[{...node('root'),agentId:undefined,receipt:{taskId:'coding-task',status:'COMPLETED',terminal:true,resultAvailable:true}}]};
 const restored=structuredClone(state);restored.version=4;restored.nodes[0].receipt={...state.nodes[0].receipt,progressSnapshotChecked:true,
  analysisEvents:[{payload:{workerActivity:{type:'MODEL_TIMING',status:'SUCCEEDED'}}}]};
 api.explorationNodeStatus.mockResolvedValue(restored);await mount(state);
 await vi.advanceTimersByTimeAsync(1501);await settle();expect(host.querySelector('[aria-label="实时分析活动"]')).toBeNull();
 await vi.advanceTimersByTimeAsync(3001);await settle();expect(api.explorationNodeStatus).toHaveBeenCalledTimes(1);expect(api.executeExploration).not.toHaveBeenCalled();
 }finally{vi.useRealTimers();}
});
it('stops an accepted file-analysis task after refresh through the existing stop endpoint',async()=>{
 const state={...structuredClone(space),nodes:[{...node('root'),agentId:undefined,receipt:{taskId:'coding-task',status:'RUNNING',terminal:false,resultAvailable:false}}]};
 const vm=await mount(state);expect(host.querySelector('.exploration-stop-bar')).not.toBeNull();
 const stopped=structuredClone(state);stopped.version=4;stopped.nodes[0].receipt={taskId:'coding-task',status:'CANCELLED',terminal:true,resultAvailable:false};
 api.stopExploration.mockResolvedValue(stopped);await vm.stopAnalysis();await settle();
 expect(api.stopExploration).toHaveBeenCalledWith('space','root',null);expect(vm.selectedState.code).toBe('CANCELLED');
 expect(api.executeExploration).not.toHaveBeenCalled();expect(host.querySelector('.exploration-stop-bar')).toBeNull();
});
it('restores a dispatched file analysis by status polling without another execution request',async()=>{
 vi.useFakeTimers();try{
 const state={...structuredClone(space),nodes:[{...node('root'),agentId:undefined,executionRequestedAt:1,receipt:{}}]};
 const running=structuredClone(state);running.version=4;running.nodes[0].receipt={taskId:'coding-task',status:'RUNNING',terminal:false,resultAvailable:false,eventCursor:2};
 const finished=structuredClone(running);finished.version=5;finished.nodes[0].receipt={taskId:'coding-task',status:'BUDGET_EXCEEDED',terminal:true,resultAvailable:false,eventCursor:4,stageSnapshotChecked:true};
 api.explorationNodeStatus.mockResolvedValueOnce(running).mockResolvedValueOnce(finished);
 const vm=await mount(state);await vi.advanceTimersByTimeAsync(1501);await settle();expect(vm.taskRunning).toBe(true);expect(vm.selectedState.code).toBe('RUNNING');
 await vi.advanceTimersByTimeAsync(1501);await settle();expect(vm.selectedState.label).toBe('预算已耗尽');expect(vm.taskRunning).toBe(false);
 expect(api.explorationNodeStatus).toHaveBeenCalledTimes(2);expect(api.executeExploration).not.toHaveBeenCalled();
 }finally{vi.useRealTimers();}
});
it('restores a budget failure without showing the previous branch report or resubmitting execution',async()=>{
 const state=structuredClone(space);state.nodes.push({...node('failed','root'),receipt:{taskId:'coding-task',status:'BUDGET_EXCEEDED',terminal:true,resultAvailable:false}});
 const vm=await mount(state);vm.selectNode(vm.space.nodes.at(-1));await settle();
 expect(vm.selectedState.label).toBe('预算已耗尽');expect(vm.canvasNode).toBeNull();expect(vm.report).toBeUndefined();
 expect(host.querySelector('.exploration-empty-result h2').textContent).toContain('未交付');
 expect(host.querySelector('[data-chart-type]')).toBeNull();expect(api.executeExploration).not.toHaveBeenCalled();
});
it('stops the original running node, prevents double clicks and preserves delivered results',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt={...state.nodes[0].receipt,agentTaskId:'task',terminal:false,status:'EXECUTING'};
 const vm=await mount(state);let finish;api.stopExploration.mockImplementation(()=>new Promise(resolve=>finish=resolve));
 const action=vm.stopAnalysis();await settle();
 expect(host.querySelector('.exploration-stop-bar button').disabled).toBe(true);expect(host.textContent).toContain('正在停止');
 await vm.stopAnalysis();expect(api.stopExploration).toHaveBeenCalledTimes(1);
 const stopped=structuredClone(state);stopped.version++;stopped.nodes[0].receipt={...stopped.nodes[0].receipt,status:'CANCELLED',terminal:true};
 finish(stopped);await action;await settle();
 expect(api.stopExploration).toHaveBeenCalledWith('space','root',null);expect(vm.selectedState.code).toBe('CANCELLED');
 expect(host.textContent).toContain('已停止');expect(host.querySelector('.exploration-stop-bar')).toBeNull();
 expect(vm.report.title).toBe('客户资产结构');expect(api.executeExploration).not.toHaveBeenCalled();
});
it('keeps the real task running when a stop request fails and allows retry',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt={agentTaskId:'task',terminal:false,status:'EXECUTING',resultAvailable:false};
 const vm=await mount(state);api.stopExploration.mockRejectedValueOnce(new Error('停止请求未确认'));
 await vm.stopAnalysis();await settle();expect(vm.selectedState.code).toBe('RUNNING');expect(vm.error).toContain('未确认');
 expect(host.querySelector('.exploration-stop-bar button').disabled).toBe(false);
});
it('does not apply a late stop response to another project',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt={agentTaskId:'task',terminal:false,status:'EXECUTING'};
 const vm=await mount(state);let finish;api.stopExploration.mockImplementation(()=>new Promise(resolve=>finish=resolve));
 const pending=vm.stopAnalysis();vm.space={id:'another-space',version:0,nodes:[]};
 finish({...state,version:4,nodes:[{...state.nodes[0],receipt:{terminal:true,status:'CANCELLED'}}]});await pending;
 expect(vm.space.id).toBe('another-space');expect(vm.notice).toBe('');
});
it('does not restore running state when the original submission returns after stopping',async()=>{
 const vm=await mount();const prepared=structuredClone(space);prepared.version=4;
 prepared.nodes.push({...node('new'),saved:false,receipt:{agentTaskId:'task',terminal:false,status:'EXECUTING',resultAvailable:false}});
 api.prepareExploration.mockResolvedValue(prepared);let finish;api.executeExploration.mockImplementation(()=>new Promise(resolve=>finish=resolve));
 const pending=vm.ask({query:'新的分析'});await settle();expect(vm.activeId).toBe('new');
 const stopped=structuredClone(prepared);stopped.version=5;stopped.nodes.at(-1).receipt={...stopped.nodes.at(-1).receipt,terminal:true,status:'CANCELLED'};
 api.stopExploration.mockResolvedValue(stopped);await vm.stopAnalysis();expect(vm.analyzing).toBe(false);
 finish(prepared);await pending;await settle();expect(vm.selectedState.code).toBe('CANCELLED');expect(vm.notice).toContain('已停止');
 expect(vm.space.version).toBe(5);expect(vm.report.title).toBe('客户资产结构');
});
it('shows compact activity while running and hides it after completion',async()=>{
 const activity={sequence:1,type:'RUNTIME_OBSERVATION',createTime:Date.now(),payload:{metadata:{eventKind:'CODING_TRACE',
   taskId:'task',workerActivity:{type:'TOOL_STARTED',tool:'input_read',toolCallId:'read'}}}};
 const pending={...node('root'),receipt:{agentTaskId:'task',terminal:false,resultAvailable:false,status:'EXECUTING',analysisEvents:[activity]}};
 const vm=await mount({...space,nodes:[pending]});
 expect(host.querySelector('[aria-label="实时分析活动"]').textContent).toContain('分析活动');
 vm.space.nodes[0].receipt.analysisEvents.push({...activity,sequence:2,payload:{metadata:{...activity.payload.metadata,
   workerActivity:{type:'TOOL_FINISHED',tool:'input_read',toolCallId:'read',status:'SUCCEEDED'}}}});
 await settle();expect(host.querySelector('[aria-label="实时分析活动"]').textContent).not.toContain('TOOL_FINISHED');
 vm.space.nodes[0].receipt={...vm.space.nodes[0].receipt,terminal:true,status:'COMPLETED',resultAvailable:true};await settle();expect(host.querySelector('[aria-label="实时分析活动"]')).toBeNull();
 vm.startRoot();await settle();expect(host.querySelector('[aria-label="实时分析活动"]')).toBeNull();
 expect(api.executeExploration).not.toHaveBeenCalled();
});
it('continues an owned archived file-analysis branch through its original backend without rebinding it to an Agent',async()=>{
 const state=structuredClone(space);delete state.nodes[0].agentId;
 const vm=await mount(state);expect(vm.canContinueLegacy).toBe(true);expect(host.querySelector('.composer-agent-footer')).toBeNull();
 const next=structuredClone(state);const child={...node('legacy-child','root'),agentId:undefined};next.nodes.push(child);api.prepareExploration.mockResolvedValue(next);api.executeExploration.mockResolvedValue(next);
 await vm.ask({query:'沿用已保存数据继续探索'});
 expect(api.prepareExploration).toHaveBeenCalledWith('space',expect.objectContaining({parentId:'root',fileIds:['file'],agentId:''}));expect(vm.space.nodes[0].agentId).toBeUndefined();
});
it('does not show an indefinite waiting state for a cancelled first analysis with no artifact',async()=>{
 const pending={...node('root'),receipt:{agentTaskId:'task',terminal:true,resultAvailable:false,status:'CANCELLED'}};
 const vm=await mount({...space,nodes:[pending]});expect(host.querySelector('.exploration-empty-result h2').textContent).toContain('未交付');expect(vm.taskRunning).toBe(false);
});
it('retains the parent chart when a child starts and historical navigation remains available',async()=>{
 const vm=await mount(),chart=host.querySelector('[data-chart-type]');
 const pending={...node('pending','root'),saved:false,receipt:{agentTaskId:'task',terminal:false,resultAvailable:false,status:'EXECUTING'}};
 const updated={...structuredClone(space),version:4,nodes:[...structuredClone(space.nodes),pending]};
 api.prepareExploration.mockResolvedValue(updated);api.executeExploration.mockResolvedValue(updated);
 await vm.ask({query:'继续探索'});await settle();expect(vm.active.id).toBe('pending');expect(vm.canvasNode.id).toBe('root');
 expect(host.querySelector('[data-chart-type]')).toBe(chart);expect(host.querySelector('.exploration-node-state').textContent).toContain('画布保留');
 vm.selectNode(vm.space.nodes[1]);await settle();expect(vm.canvasNode.id).toBe('child');expect(api.executeExploration).toHaveBeenCalledTimes(1);
});
it('continues polling after a stage result and never remounts an unrelated historical chart',async()=>{
 vi.useFakeTimers();try{
 const pending={...node('pending','root'),receipt:{...node('pending').receipt,agentTaskId:'task',terminal:false,status:'EXECUTING'}};
 const state={...structuredClone(space),nodes:[...structuredClone(space.nodes),pending]};
 const changed=structuredClone(state);changed.version=4;changed.nodes.at(-1).receipt.terminal=true;changed.nodes.at(-1).receipt.status='SUCCEEDED';
 api.explorationNodeStatus.mockResolvedValue(changed);const vm=await mount(state);const chart=host.querySelector('[data-chart-type]');
 const region=host.querySelector('.exploration-scroll-region');region.scrollTop=125;
 await vi.advanceTimersByTimeAsync(1501);await settle();expect(api.explorationNodeStatus).toHaveBeenCalledWith('space','pending');
 expect(host.querySelector('[data-chart-type]')).toBe(chart);expect(region.scrollTop).toBe(125);expect(vm.taskRunning).toBe(false);
 }finally{vi.useRealTimers();}
});
it('renders table-only results directly and chart selection only drafts a followup',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt.visualReport={title:'明细',summary:'真实明细',tables:[{id:'data',columns:['金额'],rows:[[42]]}],charts:[]};
 const vm=await mount(state);expect(host.querySelector('.exploration-table-canvas').textContent).toContain('42');expect(host.querySelector('.exploration-empty-result')).toBeNull();
 vm.chartSelection({label:'股票'});await settle();expect(vm.question).toContain('股票');expect(api.executeExploration).not.toHaveBeenCalled();expect(api.prepareExploration).not.toHaveBeenCalled();
});
it('offers per-project actions and renames without altering nodes or submitting analysis',async()=>{
 const vm=await mount();const original=JSON.stringify(vm.space.nodes);document.querySelector('.exploration-project-more').click();await settle();
 expect(document.querySelector('.exploration-project-actions').textContent).toContain('修改名称');
 await vm.manageProject(vm.spaces[0],'rename');vm.projectName='  修改后的项目  ';api.renameExploration.mockResolvedValue({...structuredClone(space),version:4,title:'修改后的项目'});
 await vm.submitProjectAction();expect(api.renameExploration).toHaveBeenCalledWith('space',{version:3,title:'修改后的项目'});expect(vm.space.title).toBe('修改后的项目');expect(vm.spaces[0].title).toBe('修改后的项目');expect(JSON.stringify(vm.space.nodes)).toBe(original);expect(api.executeExploration).not.toHaveBeenCalled();
});
it('uses the shared delete confirmation, cancellation sends no request, and failures remain retryable',async()=>{
 const vm=await mount();await vm.manageProject(vm.spaces[0],'delete');await settle();
 const dialog=document.querySelector('.app-confirm-dialog');expect(dialog.textContent).toContain('无法恢复');expect(dialog.querySelector('.app-confirm-danger')).not.toBeNull();
 vm.cancelProjectAction();expect(api.deleteExploration).not.toHaveBeenCalled();
 await vm.manageProject(vm.spaces[0],'delete');api.deleteExploration.mockRejectedValueOnce(new Error('仍有分析任务运行'));
 await vm.submitProjectAction();expect(vm.projectActionError).toContain('仍有分析任务');expect(vm.space.id).toBe('space');expect(vm.projectAction.kind).toBe('delete');
});
it('clears the deleted last project and URL so a refresh cannot restore removed history',async()=>{
 const vm=await mount();vm.question='旧项目草稿';await vm.manageProject(vm.spaces[0],'delete');api.deleteExploration.mockResolvedValue({deleted:true});
 await vm.submitProjectAction();expect(api.deleteExploration).toHaveBeenCalledWith('space',3);expect(vm.space).toBeNull();expect(vm.spaces).toHaveLength(0);expect(vm.activeId).toBe('');expect(vm.question).toBe('');expect(location.hash).not.toContain('exploration=');expect(location.hash).not.toContain('node=');
});
it('deleting a different project preserves the active selection, report and draft',async()=>{
 const vm=await mount();vm.spaces.push({id:'other',title:'另一项目'});vm.question='保留此问题';api.getExploration.mockResolvedValueOnce({id:'other',title:'另一项目',version:7,nodes:[]});
 await vm.manageProject(vm.spaces[1],'delete');api.deleteExploration.mockResolvedValue({deleted:true});await vm.submitProjectAction();
 expect(api.deleteExploration).toHaveBeenCalledWith('other',7);expect(vm.space.id).toBe('space');expect(vm.activeId).toBe('root');expect(vm.question).toBe('保留此问题');expect(vm.spaces.map(s=>s.id)).toEqual(['space']);
});
const node=(id,parentId=null)=>({id,parentId,agentId:'agent',question:'分析资产结构',fileIds:['file'],chartTypes:{},saved:true,receipt:{resultAvailable:true,visualReport:{title:'客户资产结构',summary:'真实结果',charts:[{id:'chart',kind:'plotly',title:'资产结构',artifact:{fileId:'artifact-'+id}}]},results:[{reportMarkdown:'<think>private</think>业务结论'}]}});
const space={id:'space',title:'客户资产分析',version:3,nodes:[node('root'),node('child','root'),node('sibling','root')]};
it('uploads supplemental data and includes it with inherited files when continuing analysis',async()=>{
 const vm=await mount(structuredClone(space)),file=new File(['date,pnl\n2026-07-31,47'],'补充.csv',{type:'text/csv'});
 api.uploadExplorationAttachment.mockResolvedValue({id:'supplement',fileName:file.name});
 await vm.addFiles([file]);await settle();expect(vm.attachments[0].state).toBe('ready');expect(host.querySelector('.composer-attachments').textContent).toContain('补充.csv');
 const signal=new AbortController().signal;
 await vm.suggestIdeas({draft:'核对新数据',agentId:'agent',signal});expect(api.generateExplorationIdeas).toHaveBeenCalledWith({draft:'核对新数据',agentId:'agent',workspaceId:'space',nodeId:'root',fileIds:['supplement']},{signal,workspaceContext:null});
 const next=structuredClone(space);next.nodes.push({...node('new','root'),fileIds:['file','supplement']});api.prepareExploration.mockResolvedValue(next);api.executeExploration.mockResolvedValue(next);
 await vm.ask({query:'使用补充数据核对'});expect(api.prepareExploration).toHaveBeenCalledWith('space',expect.objectContaining({parentId:'root',fileIds:['file','supplement'],question:'使用补充数据核对'}));expect(vm.attachments).toHaveLength(0);
});
it('blocks incomplete uploads and keeps the draft available for retry',async()=>{
 const vm=await mount(structuredClone(space));api.uploadExplorationAttachment.mockRejectedValueOnce(new Error('上传中断'));
 await vm.addFiles([new File(['unit=CNY'],'口径.txt',{type:'text/plain'})]);await settle();expect(vm.attachments[0].state).toBe('failed');
 await vm.ask({query:'核对口径'});expect(api.prepareExploration).not.toHaveBeenCalled();expect(vm.error).toContain('重试');
 api.uploadExplorationAttachment.mockResolvedValueOnce({id:'fixed'});await vm.retryFile(vm.attachments[0].clientId);expect(vm.attachments[0].state).toBe('ready');
});
it('preserves a long pasted text as a complete attachment instead of truncating the analysis input',async()=>{
 const vm=await mount(structuredClone(space)),original='  '+('完整口径补充资料。'.repeat(1000))+'\n';
 api.uploadExplorationAttachment.mockResolvedValue({id:'long-text'});
 const updated=structuredClone(space);updated.nodes.push(node('text-root','root'));api.prepareExploration.mockResolvedValue(updated);api.executeExploration.mockResolvedValue(updated);
 await vm.ask({query:original});const uploaded=api.uploadExplorationAttachment.mock.calls[0][0],reader=new FileReader();
 const content=new Promise(resolve=>{reader.onload=()=>resolve(reader.result);});reader.readAsText(uploaded);expect(await content).toBe(original);
 expect(api.prepareExploration).toHaveBeenCalledWith('space',expect.objectContaining({fileIds:['file','long-text'],question:expect.stringContaining('完整资料')}));
});
it('allows supplemental data after a failed analysis and creates a new root without rewriting the failed node',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt={resultAvailable:false,terminal:true,status:'INPUT_REQUIRED'};
 const vm=await mount(state);expect(host.querySelector('textarea').disabled).toBe(false);expect(host.textContent).toContain('原节点保留');
 const updated=structuredClone(state);updated.nodes.push(node('retry-root'));api.prepareExploration.mockResolvedValue(updated);api.executeExploration.mockResolvedValue(updated);
 await vm.ask({query:'补充口径后重新分析'});expect(api.prepareExploration).toHaveBeenCalledWith('space',expect.objectContaining({parentId:null,question:'补充口径后重新分析'}));expect(api.saveExplorationNode).not.toHaveBeenCalled();
});
async function mount(state=space){api.generateExplorationIdeas.mockResolvedValue({ideas:[{title:'核对当前数据',prompt:'基于当前客户资产分析，核对比较基准和新增资料'}]});api.explorationAgents.mockResolvedValue([{id:'agent',name:'Authorized agent',marketStatus:'published'}]);location.hash='#/w/data-science/dataExploration?exploration=space&node=root';api.listExplorations.mockResolvedValue([{id:'space'}]);api.getExploration.mockResolvedValue(structuredClone(state));host=document.createElement('div');document.body.append(host);app=createApp(View);const vm=app.mount(host);await settle();return vm;}
afterEach(()=>{app?.unmount();host?.remove();vi.resetAllMocks();});
it('restores and navigates without analysis or file enumeration, hides configuration and private reasoning',async()=>{
 const vm=await mount();expect(vm.active.id).toBe('root');expect(api.explorationDatasets).not.toHaveBeenCalled();expect(host.querySelector('.exploration-config,input[type=checkbox]')).toBeNull();expect(host.textContent).toContain('思维链');expect(host.querySelector('textarea').placeholder).toBe('基于当前分析继续提问…');vm.selectNode(space.nodes[1]);await vm.reload();expect(vm.active.id).toBe('child');expect(location.hash).toContain('node=child');expect(api.executeExploration).not.toHaveBeenCalled();expect(vm.authoredReport).toBe('业务结论');
});
it('changes presentation in place and rejects incompatible pie without execution',async()=>{
 const vm=await mount(),element=host.querySelector('[data-chart-type]');vm.chartReady({id:'chart',fileId:'artifact-root',figure:{data:[{type:'bar',x:['A'],y:[2]},{type:'bar',x:['A'],y:[3]}],layout:{}},compatibleTypes:['bar','line','scatter']});api.saveExplorationNode.mockResolvedValue({...space,nodes:[{...node('root'),chartTypes:{chart:'line'}},...space.nodes.slice(1)]});await vm.chartType(vm.currentChart,{target:{value:'line'}});await settle();expect(host.querySelector('[data-chart-type]')).toBe(element);expect(element.dataset.chartType).toBe('line');await vm.chartType(vm.currentChart,{target:{value:'pie'}});expect(vm.error).toContain('非负');expect(api.saveExplorationNode).toHaveBeenCalledTimes(1);expect(api.executeExploration).not.toHaveBeenCalled();
});
it('creates a child from history and does not steal selection when execution finishes',async()=>{
 const vm=await mount(),prepared={...space,version:4,nodes:[...space.nodes,{...node('new','root'),receipt:null,saved:false}]};api.prepareExploration.mockResolvedValue(prepared);let finish;api.executeExploration.mockImplementation(()=>new Promise(r=>finish=r));vm.question='继续分析变化';const running=vm.ask();await settle();expect(api.prepareExploration.mock.calls[0][1]).toMatchObject({parentId:'root',fileIds:['file']});vm.selectNode(space.nodes[1]);finish({...prepared,nodes:[...space.nodes,node('new','root')]});await running;expect(vm.active.id).toBe('child');
});
it('creates with existing authorized references without listing files',async()=>{
 const vm=await mount();api.createExploration.mockResolvedValue({id:'fresh',fileIds:['file'],nodes:[],version:0});await vm.create();expect(api.createExploration).toHaveBeenCalledWith('客户资产分析',{sourceWorkspaceId:'space',sourceNodeId:'root'});expect(vm.selected).toEqual(['file']);expect(api.explorationDatasets).not.toHaveBeenCalled();
});
it('retains recoverable execution without creating a replacement node',async()=>{
 const vm=await mount({...space,nodes:[{...node('root'),receipt:null}]});api.executeExploration.mockRejectedValue(new Error('Sandbox unavailable'));await vm.recover();expect(vm.active.id).toBe('root');expect(vm.error).toContain('Sandbox unavailable');expect(api.prepareExploration).not.toHaveBeenCalled();
});

it('starts from an authorized agent without requiring a dataset and carries agent identity into preparation',async()=>{const vm=await mount({...space,nodes:[],fileIds:[]});vm.selectAgent('agent');vm.question='分析客户资产';api.prepareExploration.mockResolvedValue({...space,nodes:[{...node('new'),receipt:null}]});api.executeExploration.mockResolvedValue({...space,nodes:[node('new')]});await vm.ask({reportPresentation:{styleProfile:'financial-cn'}});expect(api.prepareExploration.mock.calls[0][1]).toMatchObject({agentId:'agent',parentId:null,reportPresentation:{styleProfile:'financial-cn'}});expect(api.explorationDatasets).not.toHaveBeenCalled();});
it('switching agent starts a fresh analysis while keeping the typed question and old results intact',async()=>{const vm=await mount();vm.question='我已经输入的问题';vm.agents.push({id:'other',name:'Other',marketStatus:'published'});vm.selectAgent('other');expect(vm.activeId).toBe('');expect(vm.question).toBe('我已经输入的问题');expect(vm.selectedAgentId).toBe('other');expect(vm.space.nodes[0].agentId).toBe('agent');expect(api.executeExploration).not.toHaveBeenCalled();});

it('restores an Agent task by reading status without submitting or executing it again',async()=>{vi.useFakeTimers();try{const pending={...node('root'),receipt:{agentTaskId:'runtime-task',status:'EXECUTING',resultAvailable:false,terminal:false}};api.explorationNodeStatus.mockResolvedValue({...space,nodes:[node('root')]});const vm=await mount({...space,nodes:[pending]});await vi.advanceTimersByTimeAsync(1501);await settle();expect(api.explorationNodeStatus).toHaveBeenCalledWith('space','root');expect(vm.active.receipt.resultAvailable).toBe(true);expect(api.executeExploration).not.toHaveBeenCalled();expect(api.prepareExploration).not.toHaveBeenCalled();}finally{vi.useRealTimers();}});
it('does not silently bind legacy file results to a newly selected Agent',async()=>{const legacy={...node('root'),agentId:undefined};const vm=await mount({...space,nodes:[legacy]});expect(vm.selectedAgentId).toBe('');const receipt=JSON.stringify(vm.active.receipt);vm.selectAgent('agent');expect(vm.activeId).toBe('');expect(JSON.stringify(vm.space.nodes[0].receipt)).toBe(receipt);expect(api.prepareExploration).not.toHaveBeenCalled();});
it('shows dynamic chart labels and fills contextual question ideas without modifying history or starting analysis',async()=>{
 const state=structuredClone(space);state.nodes[0].receipt.visualReport.charts[0].title='资产结构（Plotly）';
 const vm=await mount(state);const archived=JSON.stringify(vm.active.receipt);
 expect(host.querySelector('.exploration-chart-card h2').textContent).toBe('资产结构（动态）');
 host.querySelector('[aria-label="提问灵感"]').click();await settle();host.querySelector('.composer-idea-option').click();await settle();
 expect(vm.question).toContain('基于当前');expect(vm.question).toContain('比较基准');expect(JSON.stringify(vm.active.receipt)).toBe(archived);
 expect(api.executeExploration).not.toHaveBeenCalled();expect(api.prepareExploration).not.toHaveBeenCalled();expect(api.saveExplorationNode).not.toHaveBeenCalled();
 vm.startRoot();await settle();expect(host.querySelector('textarea').disabled).toBe(false);
});
it('collapses the branch pane on small screens so it cannot cover the composer, and closes it after node selection',async()=>{
 const width=window.innerWidth;try{const vm=await mount();vm.question='保留草稿';window.innerWidth=390;window.dispatchEvent(new Event('resize'));await settle();expect(vm.sidebarCollapsed).toBe(true);expect(vm.question).toBe('保留草稿');vm.sidebarCollapsed=false;vm.selectNode(vm.space.nodes[1]);expect(vm.sidebarCollapsed).toBe(true);}finally{window.innerWidth=width;window.dispatchEvent(new Event('resize'));}
});
