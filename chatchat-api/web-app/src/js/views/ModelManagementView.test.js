// @vitest-environment jsdom
import {createApp, h, nextTick} from 'vue';
import {describe, it, expect, vi} from 'vitest';
import View from '../../views/ModelManagementView.vue';
import {fetchPlatformModels,savePlatformModel} from '../../services/api';
vi.mock('../../services/api',()=>({fetchPlatformModels:vi.fn(async()=>[]),savePlatformModel:vi.fn(async()=>({})),
  deletePlatformModel:vi.fn(),publishPlatformModel:vi.fn(),setDefaultPlatformModel:vi.fn()}));
describe('model request timeout form',()=>{
  it('defaults to 300 seconds, reloads per-model values, rejects invalid input and saves seconds',async()=>{
    const host=document.createElement('div');const app=createApp({render:()=>h(View)});
    try {
      const root=app.mount(host);await nextTick();const view=root.$.subTree.component.proxy;
      view.openCreate();await nextTick();
      const input=host.querySelector('input[type="number"][max="1200"]');
      expect(input.value).toBe('300');expect(host.textContent).toContain('模型请求超时时间（秒）');
      view.openEdit({name:'A',type:'chat',requestTimeoutSeconds:120});expect(view.form.requestTimeoutSeconds).toBe(120);
      view.openEdit({name:'B',type:'chat',requestTimeoutSeconds:600});expect(view.form.requestTimeoutSeconds).toBe(600);
      for(const invalid of [0,-1,1.5,1201]) {
        view.form.requestTimeoutSeconds=invalid;await view.save();expect(view.error).toBe(true);
      }
      expect(savePlatformModel).not.toHaveBeenCalled();
      view.form.requestTimeoutSeconds=600;await view.save();expect(savePlatformModel.mock.calls[0][0].requestTimeoutSeconds).toBe(600);
      view.openEdit({name:'legacy',type:'chat'});expect(view.form.requestTimeoutSeconds).toBe(300);
      expect(fetchPlatformModels).toHaveBeenCalled();
    } finally {app.unmount();}
  });
});
