// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {it,expect,afterEach,vi} from 'vitest';
import PromptComposer from './PromptComposer.vue';
let app,host;
afterEach(()=>{app?.unmount();host?.remove();});
it('requires an explicit per-request report choice and resets after send and conversation changes',async()=>{
  host=document.createElement('div');document.body.append(host);const sends=[],props=reactive({modelValue:'Analyze this dataset',conversationKey:'one'});
  app=createApp({render:()=>h(PromptComposer,{...props,onSend:value=>sends.push(value)})});app.mount(host);await nextTick();
  const toggle=host.querySelector('[role=switch]');expect(toggle.textContent).toContain('图文报告');expect(toggle.getAttribute('aria-checked')).toBe('false');
  toggle.click();await nextTick();host.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await nextTick();
  expect(sends[0].reportMode).toBe('visual');expect(toggle.getAttribute('aria-checked')).toBe('false');expect(sends[0].documentWorkflow).toBe(false);expect(sends[0]).not.toHaveProperty('webSearch');
  host.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await nextTick();expect(sends[1].reportMode).toBe('off');
  toggle.click();await nextTick();props.conversationKey='two';await nextTick();expect(toggle.getAttribute('aria-checked')).toBe('false');
  app.unmount();app=null;host.replaceChildren();app=createApp({render:()=>h(PromptComposer,props)});app.mount(host);await nextTick();expect(host.querySelector('[role=switch]').getAttribute('aria-checked')).toBe('false');
});
