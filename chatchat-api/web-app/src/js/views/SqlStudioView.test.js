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
it('opens source-level exploration without submitting, replacing drafts or recreating the editor',async()=>{
  await mount([{id:'p1',name:'Project'}]);view.schemaExplorer={assistantContext:()=> 'authorized'};
  view.layout.assistant=false;view.aiAction='generate';view.prompt='';view.exploreDatabase();
  expect(view.layout.assistant).toBe(true);expect(view.aiAction).toBe('explore');expect(view.prompt).toContain('当前数据库');
  expect(sql.prepareSqlGeneration).not.toHaveBeenCalled();
  view.prompt='my unfinished requirement';view.exploreDatabase();expect(view.prompt).toBe('my unfinished requirement');
});
it('uses verified evidence as engineering context only after explicit reference succeeds',async()=>{
  await mount([{id:'p1',name:'Project'}]);const capture=vi.fn().mockResolvedValue(false);
  view.schemaExplorer={referenceEvidence:capture};view.aiAction='explore';
  await view.referenceMetadata({tableRef:'t'});expect(view.aiAction).toBe('explore');
  capture.mockResolvedValue(true);await view.referenceMetadata({tableRef:'t'});expect(view.aiAction).toBe('generate');
  expect(sql.prepareSqlGeneration).not.toHaveBeenCalled();expect(view.message).toContain('尚未生成或执行');
});
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
it('includes only explicitly selected SQL and never passes retained engineering tables into database exploration',async()=>{
  await mount([{id:'p1',name:'Project'}]);
  view.files=[{id:'f',path:'sql/current.sql',kind:'SQL',version:2,content:'SELECT private_full_file;',draft:'SELECT private_full_file;'}];view.activeId='f';await settle();
  view.selectedCode={fileId:'f',version:2,path:'sql/current.sql',text:'SELECT chosen_fragment;'};
  view.prompt='Explain these fields';view.schemaExplorer={assistantContext:()=> 'authorized-context',codingSelection:()=>({sources:[{discoveryId:'old-explicit-engineering-reference'}]})};
  sql.prepareSqlGeneration.mockRejectedValue(new Error('stop after prepare'));await view.generate();
  const request=sql.prepareSqlGeneration.mock.calls.at(-1)[1];expect(request.prompt).toContain('SELECT chosen_fragment;');
  expect(request.prompt).not.toContain('private_full_file');expect(request.codingMetadataSelection).toBeNull();
  view.selectedCode=null;view.prompt='Find tables';await view.generate();expect(sql.prepareSqlGeneration.mock.calls.at(-1)[1].prompt).toBe('Find tables');
});
it('clears SQL attachments on file changes and routes SQL insertion through the existing editor instance only',async()=>{
  await mount([{id:'p1',name:'Project'}]);view.files=[{id:'a',path:'a.sql',kind:'SQL',version:1,content:'SELECT 1;',draft:'SELECT 1;'},
    {id:'b',path:'b.sql',kind:'SQL',version:1,content:'SELECT 2;',draft:'SELECT 2;'}];view.activeId='a';await settle();
  view.includeCurrentFile=true;view.selectedCode={path:'a.sql',text:'SELECT 1;'};view.activeId='b';await settle();
  expect(view.selectedCode).toBeNull();expect(view.includeCurrentFile).toBe(false);
  const insertSql=vi.fn(()=>true);view.editorPane={insertSql};view.insertAssistantSql('SELECT 3;',true);
  expect(insertSql).toHaveBeenCalledWith('SELECT 3;',true,'b');expect(view.files[1].content).toBe('SELECT 2;');expect(view.message).toContain('尚未保存或执行');
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
