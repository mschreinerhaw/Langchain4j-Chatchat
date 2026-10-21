// @vitest-environment jsdom
import {createApp,h,nextTick,ref} from 'vue';
import {afterEach,describe,expect,it,vi} from 'vitest';
import UserSpaceBrowser from './UserSpaceBrowser.vue';
import {apiRequest,fetchPythonCodeModels} from '../services/api';
vi.mock('../services/api',()=>({apiRequest:vi.fn(),getStoredAuthSession:()=>({token:'test-token'}),fetchPythonCodeModels:vi.fn()}));
vi.mock('./ReportMarkdown.vue',()=>({default:{props:['content'],render(){return null;}}}));
let app,host;
const native={id:'a'.repeat(64),fileName:'rules.md',kind:'DOCUMENT',size:5,state:'AVAILABLE',createdAt:1,taskId:'',runId:'',sourceRef:'upload'};
const flush=async()=>{for(let i=0;i<15;i++){await Promise.resolve();await nextTick();}};
function respond(path,options){
  if(path.includes('/browser/folders')&&options?.method==='POST')return {id:'folder',parentId:'root',name:'Rules'};
  if(path.endsWith('/browser'))return {folders:[],locations:[]};
  if(path.endsWith('/space'))return {usedBytes:5,quotaBytes:100};
  if(path.endsWith('/knowledge'))throw new Error('disabled');
  if(path.includes('/browser/files?')){
    const params=new URLSearchParams(path.split('?')[1]);
    let items=[{source:'asset',sourceId:native.id,folderId:'documents',original:native},
      {source:'python-data',sourceId:'legacy',folderId:'data',original:{id:'legacy',fileName:'old.csv',fileSize:8,status:'AVAILABLE'}},
      {source:'python-script',sourceId:'script',folderId:'scripts',original:{id:'script',fileName:'analysis.py',sourceCode:'print(1)',status:'DRAFT'}}];
    const filtered=items.filter(item=>params.get('folder')==='all'||params.get('folder')===item.folderId);
    return {items:filtered,total:filtered.length,totalFiles:3,page:1,pageSize:Number(params.get('pageSize')),folderCounts:{documents:1,data:1,scripts:1}};
  }
  return null;
}
async function mount(values={},responder=respond){
  apiRequest.mockImplementation(async(...args)=>responder(...args));fetchPythonCodeModels.mockResolvedValue([{value:'chosen',label:'Chosen',defaultModel:true}]);
  host=document.createElement('div');document.body.append(host);const events={open:vi.fn(),data:vi.fn()};
  app=createApp({render:()=>h(UserSpaceBrowser,{workspaceContext:{tenantId:'tenant',userId:'alice',workspaceId:'data-science'},
    dataFiles:[{id:'legacy',fileName:'old.csv',fileSize:8,status:'AVAILABLE'}],scripts:[{id:'script',fileName:'analysis.py',sourceCode:'print(1)',status:'DRAFT'}],canCreateScript:true,
    'onOpen-script':events.open,'onDownload-data':events.data,...(values?.value||values)})});app.mount(host);await flush();return events;
}
afterEach(()=>{app?.unmount();host?.remove();vi.resetAllMocks();vi.unstubAllGlobals();});
describe('user space file browser',()=>{
  it('refreshes the server page and totals after the parent deletes a legacy file',async()=>{
    const values=ref({});let removed=false;
    await mount(values,(path,options)=>{
      const result=respond(path,options);
      return removed&&path.includes('/browser/files?')?{...result,items:result.items.filter(item=>item.source!=='python-data'),total:2,totalFiles:2,folderCounts:{documents:1,scripts:1}}:result;
    });
    expect(host.querySelector('[aria-label="下载 old.csv"]')).not.toBeNull();
    removed=true;values.value={dataFiles:[]};await flush();
    expect(host.querySelector('[aria-label="下载 old.csv"]')).toBeNull();expect(host.querySelectorAll('tbody tr')).toHaveLength(2);
  });
  it('requests actual server pages and resets the page after a filter or page size change',async()=>{
    await mount({},(path,options)=>{
      if(path.includes('/browser/files?')){
        const params=new URLSearchParams(path.split('?')[1]);const page=Number(params.get('page')),size=Number(params.get('pageSize'));
        return {items:Array.from({length:Math.min(size,56-(page-1)*size)},(_,i)=>({source:'asset',sourceId:`file-${(page-1)*size+i}`,folderId:'documents',original:{...native,id:`file-${(page-1)*size+i}`,fileName:`file-${(page-1)*size+i}.md`}})),total:56,totalFiles:56,page,pageSize:size,folderCounts:{documents:56}};
      }return respond(path,options);
    });
    expect(host.querySelectorAll('tbody tr')).toHaveLength(25);
    [...host.querySelectorAll('button')].find(button=>button.textContent==='下一页').click();await flush();
    expect(host.querySelector('[aria-label="打开 file-25.md"]')).not.toBeNull();
    const size=host.querySelector('[aria-label="每页文件数"]');size.value='10';size.dispatchEvent(new Event('change'));await flush();
    expect(host.querySelectorAll('tbody tr')).toHaveLength(10);
    expect(apiRequest.mock.calls.filter(([path])=>path.includes('/browser/files?')).at(-1)[0]).toContain('page=1&pageSize=10');
    const input=host.querySelector('input[type=search]');input.value='rules';input.dispatchEvent(new Event('input'));await flush();
    expect(apiRequest.mock.calls.filter(([path])=>path.includes('/browser/files?')).at(-1)[0]).toContain('q=rules');
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
