// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';
import {afterEach,describe,expect,it,vi} from 'vitest';
import UserSpaceBrowser from './UserSpaceBrowser.vue';
import {apiRequest,fetchPythonCodeModels} from '../services/api';
vi.mock('../services/api',()=>({apiRequest:vi.fn(),getStoredAuthSession:()=>({token:'test-token'}),fetchPythonCodeModels:vi.fn()}));
vi.mock('./ReportMarkdown.vue',()=>({default:{props:['content'],render(){return null;}}}));
let app,host;
const native={id:'a'.repeat(64),fileName:'rules.md',kind:'DOCUMENT',size:5,state:'AVAILABLE',createdAt:1,taskId:''};
const flush=async()=>{for(let i=0;i<15;i++){await Promise.resolve();await nextTick();}};
function respond(path,options){
  if(path.includes('/browser/folders')&&options?.method==='POST')return {id:'folder',parentId:'root',name:'Rules'};
  if(path.endsWith('/browser'))return {folders:[],locations:[]};
  if(path.endsWith('/space'))return {usedBytes:5,quotaBytes:100};
  if(path.endsWith('/knowledge'))throw new Error('disabled');
  if(path.includes('?limit='))return [native];
  return null;
}
async function mount(values={},responder=respond){
  apiRequest.mockImplementation(async(...args)=>responder(...args));fetchPythonCodeModels.mockResolvedValue([{value:'chosen',label:'Chosen',defaultModel:true}]);
  host=document.createElement('div');document.body.append(host);const events={open:vi.fn(),data:vi.fn()};
  app=createApp({render:()=>h(UserSpaceBrowser,{workspaceContext:{tenantId:'tenant',userId:'alice',workspaceId:'data-science'},
    dataFiles:[{id:'legacy',fileName:'old.csv',fileSize:8,status:'AVAILABLE'}],scripts:[{id:'script',fileName:'analysis.py',sourceCode:'print(1)',status:'DRAFT'}],canCreateScript:true,
    'onOpen-script':events.open,'onDownload-data':events.data,...values})});app.mount(host);await flush();return events;
}
afterEach(()=>{app?.unmount();host?.remove();vi.resetAllMocks();vi.unstubAllGlobals();});
describe('user space file browser',()=>{
  it('keeps the server pagination offset independent of files added by knowledge search',async()=>{
    await mount({},(path,options)=>{
      if(path.includes('offset=100'))return [];
      if(path.endsWith('/knowledge'))return [];
      if(path.includes('/knowledge/search'))return [{fileId:'older',excerpt:'match'}];
      if(path.endsWith('/older'))return {...native,id:'older',fileName:'older.md'};
      if(path.includes('?limit='))return Array.from({length:100},(_,index)=>({...native,id:`file-${index}`,fileName:`file-${index}.md`}));
      return respond(path,options);
    });
    const input=host.querySelector('[aria-label="搜索用户空间文件"]');input.value='match';input.dispatchEvent(new Event('input'));await flush();
    [...host.querySelectorAll('button')].find(button=>button.textContent==='检索内容').click();await flush();
    [...host.querySelectorAll('button')].find(button=>button.textContent==='加载更多文件').click();await flush();
    expect(apiRequest).toHaveBeenCalledWith('/data-science/user-assets?limit=100&offset=100',expect.any(Object));
    expect(host.textContent).not.toContain('还有更早的文件');
  });
  it('renders one file surface with purpose folders, theme buttons and preserved script/data actions',async()=>{
    const events=await mount();expect(host.textContent).toContain('用户空间');expect(host.textContent).toContain('参考文档');
    expect(host.querySelectorAll('tbody tr')).toHaveLength(3);expect(host.querySelector('.primary-button').textContent).toContain('上传文件');
    host.querySelector('[aria-label="打开 analysis.py"]').click();await flush();expect(events.open).toHaveBeenCalledWith(expect.objectContaining({id:'script'}));
    host.querySelector('[aria-label="下载 old.csv"]').click();await flush();expect(events.data).toHaveBeenCalledWith(expect.objectContaining({id:'legacy'}));
    host.querySelector('[aria-label="网格视图"]').click();await flush();expect(host.querySelectorAll('.space-file-grid article')).toHaveLength(3);
    expect(host.querySelector('.user-asset-library')).toBeNull();
  });
  it('creates a logical folder and persists selected file movement by IDs',async()=>{
    await mount();[...host.querySelectorAll('button')].find(button=>button.textContent==='新建文件夹').click();await flush();
    const dialog=document.querySelector('[aria-label="新建文件夹"]');const input=dialog.querySelector('input');input.value='Rules';input.dispatchEvent(new Event('input'));
    dialog.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await flush();
    expect(apiRequest).toHaveBeenCalledWith('/data-science/user-assets/browser/folders',expect.objectContaining({body:JSON.stringify({parentId:'root',name:'Rules'})}));
    host.querySelector('.space-folders>button').click();await flush();host.querySelector('[aria-label="选择 rules.md"]').click();await flush();
    [...host.querySelectorAll('button')].find(button=>button.textContent==='移动到').click();await flush();
    const move=document.querySelector('[aria-label="移动文件"]');const select=move.querySelector('select');select.value='documents';select.dispatchEvent(new Event('change'));
    move.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));await flush();
    const call=apiRequest.mock.calls.find(([path,options])=>path.endsWith('/locations')&&options.method==='PUT');
    expect(JSON.parse(call[1].body)).toEqual({folderId:'documents',entries:[{source:'asset',sourceId:native.id}]});
  });
  it('keeps native file analysis on explicit IDs and does not auto-select history',async()=>{
    await mount();host.querySelector('[aria-label="选择 rules.md"]').click();await flush();
    [...host.querySelectorAll('button')].find(button=>button.textContent==='分析选中文件').click();await flush();
    const analysis=host.querySelector('.space-analysis');const textarea=analysis.querySelector('textarea');textarea.value='Explain the rules';textarea.dispatchEvent(new Event('input'));await flush();
    apiRequest.mockImplementation(async(path,options)=>path.endsWith('/analyses')?{resultAvailable:true,results:[{reportMarkdown:'report'}]}:respond(path,options));
    analysis.querySelector('.primary-button').click();await flush();
    const call=apiRequest.mock.calls.find(([path])=>path.endsWith('/analyses'));const body=JSON.parse(call[1].body);
    expect(body.fileIds).toEqual([native.id]);expect(body.contextIds).toEqual([]);expect(body.modelName).toBe('chosen');
  });
});
