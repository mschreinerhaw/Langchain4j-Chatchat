// @vitest-environment jsdom
import { createApp, h, nextTick, ref } from 'vue';
import { afterEach, expect, it } from 'vitest';
import ChatAssistantView from './ChatAssistantView.js';

let app, host;
afterEach(() => { app?.unmount(); host?.remove(); });
async function mountHistory(initial) {
  host=document.createElement('div');document.body.append(host);
  const selected=ref(initial);let view;
  // Keep the actual data, computed properties, watcher and restore method; avoid
  // unrelated agent discovery and render only the restored messages for assertions.
  const component={...ChatAssistantView, mounted() {}, render() {
    return h('div',this.messages.map(message=>h('p',{'data-role':message.role},message.content)));
  }};
  app=createApp({render:()=>h(component,{ref:instance=>{view=instance;},selectedConversation:selected.value,
    userId:'owner',tenantId:'tenant'})});
  app.mount(host);await nextTick();
  return {get view(){return view;},selected};
}

it('restores history already fetched before the chat component mounts, including cancellation and steps',async()=>{
  const mounted=await mountHistory({id:'session',status:'cancelled',messages:[
    {id:'question',role:'user',content:'saved question'},
    {id:'response',role:'assistant',content:'',status:'streaming',streaming:true,taskId:'task',
      steps:[{id:'work',title:'analysis',status:'active'}]}
  ]});
  expect(mounted.view.historyId).toBe('session');
  expect(host.querySelectorAll('[data-role]').length).toBe(2);
  expect(mounted.view.conversationStatus).toBe('cancelled');
  expect(mounted.view.messages[1]).toMatchObject({streaming:false,status:'cancelled',taskId:'task'});
  expect(mounted.view.messages[1].steps[0].status).toBe('cancelled');
});

it('still hydrates a summary replaced by full detail after mount, preserving rich response metadata',async()=>{
  const mounted=await mountHistory({id:'session',status:'completed',detailsLoading:true,messages:[]});
  mounted.selected.value={id:'session',status:'completed',detailsLoaded:true,messages:[
    {id:'answer',role:'assistant',content:'saved answer',modelName:'actual-model',
      uiResponse:{answer:'saved answer',visualization:{type:'table'}}}
  ]};
  await nextTick();
  expect(host.textContent).toContain('saved answer');
  expect(mounted.view.messages[0].modelName).toBe('actual-model');
  expect(mounted.view.messages[0].uiResponse.visualization.type).toBe('table');
});

it('rebuilds saved analysis branches from message metadata when the history header has an empty tree',async()=>{
  const mounted=await mountHistory({id:'analysis',status:'completed',analysisTree:{},messages:[
    {id:'q1',role:'user',content:'root question',analysisNodeId:'root'},
    {id:'a1',role:'assistant',content:'root answer',analysisNodeId:'root'},
    {id:'q2',role:'user',content:'drill down',analysisNodeId:'child',analysisParentNodeId:'root',
      analysisSelection:{field:'region',value:'east'}},
    {id:'a2',role:'assistant',content:'branch answer',analysisNodeId:'child',analysisParentNodeId:'root'}
  ]});
  expect(mounted.view.analysisTree.rootNodeIds).toEqual(['root']);
  expect(mounted.view.analysisTree.activeNodeId).toBe('child');
  expect(mounted.view.analysisTree.nodes[1]).toMatchObject({id:'child',parentId:'root',responseMessageId:'a2',
    selection:{field:'region',value:'east'}});
});
