// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';
import {afterEach,expect,it,vi} from 'vitest';
import SqlAssistantMessage from './SqlAssistantMessage.vue';
let app,host;
afterEach(()=>{app?.unmount();host?.remove();vi.unstubAllGlobals();});
async function mount(props){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(SqlAssistantMessage,props)});app.mount(host);await nextTick();}
it('keeps SQL literal, exposes explicit editor actions and never executes model HTML',async()=>{
  const insert=vi.fn(),replace=vi.fn();await mount({content:"说明\n```sql\nSELECT '<img src=x onerror=bad()>';\n```\n<script>bad()</script>",insertable:true,replaceable:false,onInsert:insert,onReplace:replace});
  expect(host.querySelector('script,img')).toBeNull();expect(host.querySelector('code').textContent).toContain('<img');
  const buttons=[...host.querySelectorAll('button')];buttons.find(b=>b.textContent==='插入编辑器').click();
  expect(insert).toHaveBeenCalledWith("SELECT '<img src=x onerror=bad()>';");expect(replace).not.toHaveBeenCalled();
  expect(buttons.find(b=>b.textContent==='替换选中 SQL').disabled).toBe(true);
});
it('copies SQL on an HTTP deployment through the existing browser clipboard fallback',async()=>{
  const copy=vi.fn(()=>true);Object.defineProperty(document,'execCommand',{value:copy,configurable:true});
  vi.stubGlobal('navigator',{});await mount({sql:'SELECT 1;'});host.querySelector('button').click();await nextTick();
  expect(copy).toHaveBeenCalledWith('copy');expect(host.textContent).toContain('已复制');
  expect(document.body.querySelectorAll('textarea')).toHaveLength(0);
});
