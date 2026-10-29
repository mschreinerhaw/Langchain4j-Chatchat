// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const sql=vi.hoisted(()=>Object.fromEntries(['sqlProjects','sqlFiles','sqlGenerations','sqlReferences','sqlFolders','sqlAgentOptions','deleteSqlProject','sqlGeneration','prepareSqlGeneration'].map(name=>[name,vi.fn()])));
vi.mock('../../services/sqlStudioApi',()=>sql);
vi.mock('../../components/sql-studio/SqlEditorPane.vue',()=>({default:{render:()=>null}}));
vi.mock('../../components/editor/CodeChangeReview.vue',()=>({default:{render:()=>null}}));
vi.mock('../../services/api',async original=>({...await original(),fetchPythonCodeModels:vi.fn().mockResolvedValue([])}));
import SqlStudioView from './SqlStudioView';
import {readWorkspaceDraft,saveWorkspaceDraft} from '../utils/workspaceDrafts';
const context={tenantId:'tenant',userId:'owner',workspaceId:'data-science'};
let app,view;
async function settle(){for(let index=0;index<20;index++)await Promise.resolve();await nextTick();}
async function mount(projects){
  sql.sqlProjects.mockResolvedValue(projects);
  const host=document.createElement('div');document.body.append(host);
  app=createApp({setup(){view=reactive(SqlStudioView.setup({workspaceContext:context,tenantId:'tenant',userId:'owner'},{emit:vi.fn()}));return()=>h('div');}});
  app.mount(host);await settle();return host;
}
beforeEach(()=>{
  vi.clearAllMocks();localStorage.clear();
  for(const name of ['sqlFiles','sqlGenerations','sqlReferences','sqlFolders','sqlAgentOptions'])sql[name].mockResolvedValue([]);
  sql.deleteSqlProject.mockResolvedValue(true);
});
afterEach(()=>{app?.unmount();document.body.innerHTML='';});
function seedAssistant(){view.generationId='old-generation';view.result={task:{taskId:'sql-coding-old',status:'SUCCESS'}};view.chatTurns=[{id:'old-answer'}];view.prompt='old prompt';view.reviewOpen=true;view.pollError='old error';}
it('blocks exploration while the selected source is missing or being reauthorized',async()=>{
  await mount([{id:'p1',name:'Project'}]);view.prompt='find tables';
  view.schemaExplorer={assistantContext:()=>null};await view.generate();
  expect(sql.prepareSqlGeneration).not.toHaveBeenCalled();expect(view.prompt).toBe('find tables');
  expect(view.error).toContain('请先选择数据源');
});
it('passes the actual shared binding to exploration without requiring explicit tables',async()=>{
  await mount([{id:'p1',name:'Project'}]);view.prompt='find tables';
  view.schemaExplorer={assistantContext:()=> 'current-authorized-context',codingSelection:()=>null};
  sql.prepareSqlGeneration.mockRejectedValue(new Error('stop after prepare'));
  await view.generate();expect(sql.prepareSqlGeneration).toHaveBeenCalledWith('p1',expect.objectContaining({
    action:'explore',metadataBindingContextId:'current-authorized-context',codingMetadataSelection:null,
  }));
});
it('clears assistant output and saved selection when deleting the last project',async()=>{
  await mount([{id:'p1',name:'Project',dialect:'postgresql'}]);seedAssistant();
  saveWorkspaceDraft('sql-studio-project-p1',context,{conversationGenerationId:'old-generation'});
  const deletion=view.deleteProject();await settle();view.resolveConfirm(true);await deletion;
  expect(sql.deleteSqlProject).toHaveBeenCalledWith('p1');
  expect(view.projectId).toBe('');expect(view.generationId).toBe('');expect(view.result).toBeNull();
  expect(view.chatTurns).toEqual([]);expect(view.prompt).toBe('');expect(view.reviewOpen).toBe(false);expect(view.pollError).toBe('');
  expect(readWorkspaceDraft('sql-studio-project-p1',context)).toBeNull();
  expect(readWorkspaceDraft('sql-studio-selection',context)).toEqual({projectId:''});
});
it('retains project and assistant state when the server rejects deletion of an active task',async()=>{
  await mount([{id:'p1',name:'Project'}]);seedAssistant();
  sql.deleteSqlProject.mockRejectedValue(new Error('Task still running'));
  const deletion=view.deleteProject();await settle();view.resolveConfirm(true);await deletion;
  expect(view.projectId).toBe('p1');expect(view.result.task.taskId).toBe('sql-coding-old');expect(view.chatTurns).toHaveLength(1);
  expect(view.error).toBe('Task still running');
});
it('discards deleted project results before waiting for the next project to load',async()=>{
  await mount([{id:'p1',name:'First'},{id:'p2',name:'Second'}]);seedAssistant();
  let resolveFiles;sql.sqlFiles.mockImplementation(id=>id==='p2'?new Promise(resolve=>{resolveFiles=resolve;}):Promise.resolve([]));
  const deletion=view.deleteProject();await settle();view.resolveConfirm(true);await settle();
  expect(view.result).toBeNull();expect(view.generationId).toBe('');expect(view.chatTurns).toEqual([]);
  resolveFiles([]);await deletion;expect(view.projectId).toBe('p2');expect(view.result).toBeNull();
});
