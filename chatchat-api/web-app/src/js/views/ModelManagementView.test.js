// @vitest-environment jsdom
import {createApp, h, nextTick} from 'vue';
import {describe, it, expect, vi} from 'vitest';
import View from '../../views/ModelManagementView.vue';
import {fetchPlatformModels,savePlatformModel,apiRequest} from '../../services/api';
vi.mock('../../services/api',()=>({fetchPlatformModels:vi.fn(async()=>[]),savePlatformModel:vi.fn(async()=>({})),
  apiRequest:vi.fn(async()=>[{id:'tavily',name:'Tavily',enabled:true,configured:true,status:'NOT_TESTED'}]),
  deletePlatformModel:vi.fn(),publishPlatformModel:vi.fn(),setDefaultPlatformModel:vi.fn()}));
describe('model request timeout form',()=>{
  it('maintains named engine endpoints with write-only keys and saves model choices independently',async()=>{
    const host=document.createElement('div'),app=createApp({render:()=>h(View)});
    try{
      const root=app.mount(host);await nextTick();const view=root.$.subTree.component.proxy;
      view.openEdit({name:'A',type:'chat',searchProviderIds:['first','second']});
      expect(view.form.searchProviderIds).toEqual(['first','second']);
      view.openEdit({name:'B',type:'chat',searchProviderIds:['second']});expect(view.form.searchProviderIds).toEqual(['second']);
      const provider={id:'custom',name:'Custom engine',endpoint:'https://search.example.com/search',kind:'TAVILY',enabled:true,configured:true};
      apiRequest.mockImplementation(async(path,options)=>options?.method==='POST'?provider:[provider]);
      await view.openProviderManager();view.editProvider(provider);
      expect(view.providerForm.apiKey).toBe('');expect(view.providerForm.endpoint).toBe(provider.endpoint);
      view.providerForm.apiKey='new-secret';await view.saveProvider();
      const write=apiRequest.mock.calls.findLast(([,options])=>options?.method==='POST');
      expect(JSON.parse(write[1].body)).toMatchObject({name:'Custom engine',endpoint:provider.endpoint,apiKey:'new-secret'});
      expect(view.providerForm.apiKey).toBe('');
      view.closeProviderManager();expect(view.providerForm.id).toBe('');
      view.openEdit({name:'A',type:'chat',internetSearchEnabled:true,searchProviderIds:['custom']});
      await view.save();expect(savePlatformModel.mock.calls.at(-1)[0]).toMatchObject({searchProviderIds:['custom'],internetSearchEnabled:true});
      view.openEdit({name:'A',type:'chat',internetSearchEnabled:true,searchProviderIds:[]});
      savePlatformModel.mockClear();await view.save();expect(savePlatformModel).not.toHaveBeenCalled();
    }finally{app.unmount();apiRequest.mockReset();apiRequest.mockResolvedValue([{id:'tavily',name:'Tavily',enabled:true,configured:true}]);savePlatformModel.mockClear();}
  });
  it('keeps Internet choices model-specific and saves the live off switch without Provider credentials',async()=>{
    const host=document.createElement('div');const app=createApp({render:()=>h(View)});
    try{const root=app.mount(host);await nextTick();const view=root.$.subTree.component.proxy;
      view.openEdit({name:'A',type:'chat',internetSearchEnabled:true,searchProviderId:'tavily'});
      expect(view.form.internetSearchEnabled).toBe(true);expect(view.form.searchProviderId).toBe('tavily');
      view.openEdit({name:'B',type:'chat'});expect(view.form.internetSearchEnabled).toBe(false);expect(view.form.searchProviderId).toBe('');
      view.openEdit({name:'A',type:'chat',internetSearchEnabled:true,searchProviderId:'tavily'});
      view.form.internetSearchEnabled=false;await view.save();
      expect(savePlatformModel.mock.calls.at(-1)[0]).toMatchObject({internetSearchEnabled:false,searchProviderId:'tavily'});
      expect(Object.keys(savePlatformModel.mock.calls.at(-1)[0])).not.toContain('searchApiKey');
    }finally{app.unmount();savePlatformModel.mockClear();}
  });
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
