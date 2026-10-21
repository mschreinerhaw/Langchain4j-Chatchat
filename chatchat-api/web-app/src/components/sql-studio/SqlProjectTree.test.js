// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';
import {it,expect,afterEach} from 'vitest';
import SqlProjectTree from './SqlProjectTree.vue';
let app,host;
afterEach(()=>{app?.unmount();host?.remove();});
it('offers folder operations only for the actual folder context target',async()=>{
 const edited=[],deleted=[];
 host=document.createElement('div');document.body.append(host);
 app=createApp({render:()=>h(SqlProjectTree,{folders:['parent','parent/child'],files:[],references:[],mappingIds:[],selectedFolder:'parent',onEditFolder:path=>edited.push(path),onDeleteFolder:path=>deleted.push(path)})});app.mount(host);await nextTick();
 const open=async selector=>{host.querySelector(selector).dispatchEvent(new MouseEvent('contextmenu',{bubbles:true,cancelable:true,clientX:100,clientY:100}));await nextTick();};
 await open('[data-folder="parent/child"]');
 [...host.querySelectorAll('[role=menuitem]')].find(item=>item.textContent.includes('重命名')).click();await nextTick();expect(edited).toEqual(['parent/child']);
 await open('[data-folder="parent/child"]');[...host.querySelectorAll('[role=menuitem]')].find(item=>item.textContent.includes('删除')).click();await nextTick();expect(deleted).toEqual(['parent/child']);
 await open('aside');expect([...host.querySelectorAll('[role=menuitem]')].some(item=>item.textContent.includes('删除'))).toBe(false);
});
