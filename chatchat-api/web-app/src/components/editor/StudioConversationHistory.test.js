// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {it,expect,vi,afterEach} from 'vitest';
import History from './StudioConversationHistory.vue';
let app,host;
async function flush(){for(let i=0;i<8;i++)await nextTick();}
async function mount(remove=vi.fn()){
  const props=reactive({open:true,entries:[{id:'one',title:'Saved question'}],remove});
  host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(History,props)});app.mount(host);await flush();return props;
}
function menu(){document.querySelector('[aria-haspopup=menu]').click();}
async function askDelete(){menu();await flush();document.querySelector('[role=menuitem]').click();await flush();}
afterEach(()=>{app?.unmount();document.body.innerHTML='';});
it('uses the system confirmation and cancelling never deletes a conversation',async()=>{
  const remove=vi.fn();await mount(remove);await askDelete();
  const confirm=document.querySelector('.app-confirm-dialog');expect(confirm.textContent).toContain('工程文件不受影响');
  [...confirm.querySelectorAll('button')].find(b=>b.textContent==='取消').click();await flush();
  expect(remove).not.toHaveBeenCalled();expect(document.querySelector('.studio-history-select').textContent).toContain('Saved question');
});
it('waits for server success, prevents duplicate deletion, then closes only confirmation',async()=>{
  let done;const remove=vi.fn(()=>new Promise(resolve=>{done=resolve;}));const props=await mount(remove);await askDelete();
  document.querySelector('.app-confirm-submit').click();await flush();expect(remove).toHaveBeenCalledWith('one');
  expect(document.querySelector('.app-confirm-submit').disabled).toBe(true);expect(document.querySelector('.studio-history-select').disabled).toBe(true);
  props.entries=[];done();await flush();expect(document.querySelector('.app-confirm-dialog')).toBeNull();expect(document.querySelector('.studio-history-dialog').open).toBe(true);expect(document.body.textContent).toContain('暂无历史会话');
});
it('preserves the entry on server rejection and displays the real error',async()=>{
  await mount(vi.fn().mockRejectedValue(new Error('Task still running')));await askDelete();document.querySelector('.app-confirm-submit').click();await flush();
  expect(document.querySelector('[role=alert]').textContent).toBe('Task still running');expect(document.querySelector('.studio-history-select')).not.toBeNull();
});
it('disables both restore and delete while a task is running',async()=>{
  const props=await mount();props.busy=true;await flush();expect(document.querySelector('.studio-history-select').disabled).toBe(true);expect(document.querySelector('[aria-haspopup=menu]').disabled).toBe(true);
});
