// @vitest-environment jsdom
import { createApp,nextTick } from 'vue';
import { beforeEach,it,expect,vi } from 'vitest';
import WorkspaceSettingsView from './WorkspaceSettingsView.vue';
import { apiRequest } from '../services/api.js';
vi.mock('../services/api.js',()=>({apiRequest:vi.fn()}));
const row={manifest:{workspaceId:'example',name:'业务空间',configVersion:3,entryPage:'chat',pages:[{id:'chat',componentKey:'chat',props:{custom:'preserve'}}]},status:'enabled',accessPermissionCode:'workspace:example:access',version:7};
beforeEach(()=>{vi.resetAllMocks();apiRequest.mockResolvedValue([row]);});
async function mount(){const element=document.createElement('div');document.body.append(element);const app=createApp(WorkspaceSettingsView);const vm=app.mount(element);await new Promise(r=>setTimeout(r,0));await nextTick();return {vm,close(){app.unmount();element.remove();}};}
it('saves against the original version without mutating the loaded manifest',async()=>{
 const view=await mount();view.vm.edit(view.vm.rows[0]);view.vm.draft.manifest.name='更新后的空间';await view.vm.save();
 const write=apiRequest.mock.calls.find(call=>call[1]?.method==='PUT');const payload=JSON.parse(write[1].body);expect(payload.expectedVersion).toBe(7);expect(payload.manifest.configVersion).toBe(4);expect(payload.manifest.pages[0].props.custom).toBe('preserve');expect(row.manifest.name).toBe('业务空间');expect(view.vm.draft).toBeNull();view.close();
});
it('retains the draft and reports concurrent configuration conflicts',async()=>{
 const view=await mount();view.vm.edit(view.vm.rows[0]);apiRequest.mockImplementation(async(path,options)=>{if(options?.method==='PUT')throw new Error('配置已变更，请刷新');return [row];});await view.vm.save();expect(view.vm.formError).toContain('配置已变更');expect(view.vm.draft.manifest.workspaceId).toBe('example');view.close();
});
