// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {it,expect} from 'vitest';
import AssistantLayout from './AssistantLayout.vue';
it('collapses the system navigation on exploration entry while allowing manual expansion',async()=>{
 const props=reactive({activeView:'chat',layoutType:'assistant'}),host=document.createElement('div');document.body.append(host);
 const app=createApp({render:()=>h(AssistantLayout,props)});app.mount(host);
 try{
  expect(host.querySelector('.assistant-app').classList.contains('sidebar-collapsed')).toBe(false);
  props.activeView='dataExploration';props.layoutType='studio';await nextTick();expect(host.querySelector('.assistant-app').classList.contains('sidebar-collapsed')).toBe(true);
  host.querySelector('.sidebar-toggle').click();await nextTick();expect(host.querySelector('.assistant-app').classList.contains('sidebar-collapsed')).toBe(false);
  props.activeView='chat';await nextTick();host.querySelector('.sidebar-toggle').click();await nextTick();
  props.activeView='search';await nextTick();expect(host.querySelector('.assistant-app').classList.contains('sidebar-collapsed')).toBe(true);
  props.activeView='dataExploration';await nextTick();expect(host.querySelector('.assistant-app').classList.contains('sidebar-collapsed')).toBe(true);
 }finally{app.unmount();host.remove();}
});
