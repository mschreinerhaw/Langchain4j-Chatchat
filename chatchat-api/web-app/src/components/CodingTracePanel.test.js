// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {describe,it,expect,vi} from 'vitest';
import CodingTracePanel from './CodingTracePanel.vue';
import {apiRequest} from '../services/api';
vi.mock('../services/api',()=>({apiRequest:vi.fn()}));
describe('Coding trace artifacts',()=>{
  it('deduplicates replayed events and loads code and repair diff only on request',async()=>{
    const entries=[1,2].map((sequence)=>({metadata:{eventKind:'CODING_TRACE',eventId:`event-${sequence}`,
      taskId:'task',stepId:`cline-${String(sequence).repeat(64)}`,sequence,codingEventType:'EXECUTION_FINISHED',
      occurredAt:`2026-10-17T01:00:0${sequence}Z`,eventState:'RUNNING'}}));
    const props=reactive({runId:'run',observations:[...entries,entries[0]]});
    const host=document.createElement('div');document.body.append(host);
    const app=createApp({render:()=>h(CodingTracePanel,props)});app.mount(host);
    const settle=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick();};
    const click=async(label)=>{[...host.querySelectorAll('button')].find(button=>button.textContent===label).click();await settle();};
    expect(host.querySelectorAll('article')).toHaveLength(2);expect(apiRequest).not.toHaveBeenCalled();
    apiRequest.mockResolvedValueOnce({content:'raise ValueError()',truncated:false});
    await click('查看代码');
    expect(host.textContent).toContain('raise ValueError()');
    apiRequest.mockResolvedValueOnce({content:'raise ValueError()'}).mockResolvedValueOnce({content:'print(6.5)'});
    await click('与上一轮代码对比');
    expect(host.querySelector('.deleted').textContent).toContain('raise ValueError()');
    expect(host.querySelector('.added').textContent).toContain('print(6.5)');
    apiRequest.mockRejectedValueOnce(new Error('artifact unavailable'));
    await click('与上一轮代码对比');
    expect(host.querySelector('.added')).toBeNull();
    expect(host.textContent).toContain('代码版本尚未归档或当前用户无权查看');
    props.runId='other-run';props.observations=[];await nextTick();
    expect(host.textContent).not.toContain('raise ValueError()');app.unmount();host.remove();
  });
});
