// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {afterEach,expect,it,vi} from 'vitest';
import SqlSearchSelect from './SqlSearchSelect.vue';
let app,host;
afterEach(()=>{app?.unmount();host?.remove();});
async function mount(extra={}){
  const changed=vi.fn(),more=vi.fn(),props=reactive({modelValue:'a',label:'选择数据源',options:[{value:'a',label:'Orders source'},{value:'b',label:'Other source'}],...extra});
  host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(SqlSearchSelect,{...props,'onUpdate:modelValue':v=>{props.modelValue=v;changed(v);},onMore:more})});app.mount(host);await nextTick();
  return{props,changed,more,input:host.querySelector('input')};
}
it('searches and chooses by keyboard with one combobox and no second search input',async()=>{
  const {input,changed}=await mount();expect(host.querySelectorAll('input')).toHaveLength(1);expect(input.value).toBe('Orders source');
  input.focus();input.value='other';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();
  expect(host.querySelectorAll('[role=option]')).toHaveLength(1);
  input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));await nextTick();
  expect(changed).toHaveBeenCalledWith('b');expect(input.value).toBe('Other source');expect(input.getAttribute('aria-expanded')).toBe('false');
});
it('does not convert arbitrary text into an authorized value and restores the selection on Escape',async()=>{
  const {input,changed}=await mount();input.focus();input.value='unauthorized';input.dispatchEvent(new Event('input',{bubbles:true}));await nextTick();
  input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));expect(changed).not.toHaveBeenCalled();
  input.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));await nextTick();expect(input.value).toBe('Orders source');
});
it('loads additional authorized options without changing selection and closes when disabled',async()=>{
  const {props,input,more,changed}=await mount({hasMore:true});input.focus();await nextTick();host.querySelector('.sql-search-select-more').click();
  expect(more).toHaveBeenCalledOnce();expect(changed).not.toHaveBeenCalled();
  props.disabled=true;await nextTick();expect(input.disabled).toBe(true);expect(host.querySelector('[role=listbox]')).toBeNull();
});
