// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {afterEach,describe,expect,it,vi} from 'vitest';
import SqlUserDocuments from './SqlUserDocuments.vue';
import {apiRequest} from '../../services/api';
vi.mock('../../services/api',()=>({apiRequest:vi.fn()}));
let app,host;
const fixture=(id,name)=>({id,fileName:name,kind:'DOCUMENT',state:'AVAILABLE',size:12});
const flush=async()=>{for(let i=0;i<8;i++){await Promise.resolve();await nextTick();}};
async function mount(selections=[]){
  const state=reactive({modelValue:selections,busy:false});host=document.createElement('div');document.body.append(host);
  app=createApp({render:()=>h(SqlUserDocuments,{...state,'onUpdate:modelValue':value=>state.modelValue=value})});app.mount(host);await flush();return state;
}
afterEach(()=>{app?.unmount();host?.remove();vi.resetAllMocks();});
describe('SQL Studio user document references',()=>{
  it('selects only owned available documents and lets the user choose rules or evidence',async()=>{
    apiRequest.mockResolvedValue([fixture('a','rules.md'),{...fixture('b','photo.png')}, {...fixture('c','code.sql'),kind:'CODE'}]);
    const state=await mount();expect(host.textContent).toContain('rules.md');expect(host.textContent).not.toContain('photo.png');
    host.querySelector('input[type=checkbox]').click();await flush();
    expect(state.modelValue).toEqual([{fileId:'a',fileName:'rules.md',purpose:'RULES'}]);
    const select=host.querySelector('select');select.value='EVIDENCE';select.dispatchEvent(new Event('change'));await flush();
    expect(state.modelValue[0].purpose).toBe('EVIDENCE');host.querySelector('input[type=checkbox]').click();await flush();
    expect(state.modelValue).toEqual([]);expect(apiRequest).toHaveBeenCalledTimes(1);
  });
  it('uploads multiple originals into user space and selects returned IDs without importing project copies',async()=>{
    apiRequest.mockImplementation(async(path,options)=>options?.method==='POST'?fixture(options.body.get('file').name,options.body.get('file').name):[]);
    const state=await mount();const input=host.querySelector('input[type=file]');
    Object.defineProperty(input,'files',{value:[new File(['rules'],'rules.sql'),new File(['facts'],'facts.json')]});
    input.dispatchEvent(new Event('change'));await flush();
    expect(state.modelValue.map(item=>item.fileId)).toEqual(['rules.sql','facts.json']);
    const uploads=apiRequest.mock.calls.filter(([,options])=>options?.method==='POST');expect(uploads).toHaveLength(2);
    expect(uploads.every(([path])=>path==='/data-science/user-assets')).toBe(true);
    expect(host.textContent).not.toContain('正在保存');
  });
  it('shows failed uploads and unavailable selections without silently dropping references',async()=>{
    apiRequest.mockImplementation(async(path,options)=>{if(path.includes('/missing')||options?.method==='POST')throw new Error('服务暂不可用');return [];});
    const state=await mount([{fileId:'missing',fileName:'deleted.md',purpose:'RULES'}]);
    expect(host.textContent).toContain('已不可用');expect(state.modelValue).toHaveLength(1);
    [...host.querySelectorAll('button')].find(button=>button.textContent==='取消引用').click();await flush();expect(state.modelValue).toEqual([]);
    const input=host.querySelector('input[type=file]');Object.defineProperty(input,'files',{value:[new File(['x'],'rule.md')]});input.dispatchEvent(new Event('change'));await flush();
    expect(host.querySelector('[role=alert]').textContent).toContain('服务暂不可用');expect(state.modelValue).toEqual([]);
  });
});
