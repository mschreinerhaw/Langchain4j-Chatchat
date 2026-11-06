// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {it,expect,afterEach,vi} from 'vitest';
import Panel from './ExplorationActivityPanel.vue';
let app,host;
const settle=async()=>{for(let i=0;i<5;i++)await nextTick();};
const event=(sequence,type,extra={})=>({eventId:`e-${sequence}`,sequence,createTime:sequence*1000,type:'RUNTIME_OBSERVATION',payload:{metadata:{
  eventKind:'CODING_TRACE',taskId:'task',workerActivity:{type,tool:'input_read',toolCallId:'read-1',status:'SUCCEEDED',...extra}}}});
async function mount(props){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Panel,props)});app.mount(host);await settle();}
afterEach(()=>{app?.unmount();host?.remove();vi.useRealTimers();});
it('separates model response and stopped Python from a missing report using actual receipt fields',async()=>{
  await mount({running:false,status:'FAILED',events:[event(1,'MODEL_TIMING',{status:'SUCCEEDED'}),
    event(2,'TOOL_FINISHED',{tool:'python_execute',executorStopped:true,status:'SUCCEEDED',resultAvailable:false,outputStatus:'REQUIRED_OUTPUT_MISSING'})]});
  const stages=host.querySelector('[aria-label="分析交付状态"]').textContent;
  expect(stages).toContain('模型响应已响应');expect(stages).toContain('Python 执行进程已结束');
  expect(stages).toContain('报告交付交付未通过');expect(stages).not.toContain('已归档');
});
it('restores partial archived delivery from the current task receipt when old events are absent',async()=>{
  await mount({running:false,status:'COMPLETED',events:[],receipt:{resultAvailable:true,executionMode:'TEXT_ONLY',visualReport:{status:'PARTIAL'}}});
  expect(host.querySelector('[aria-label="分析交付状态"]').textContent).toContain('报告交付部分归档');
  expect(host.querySelector('[aria-label="分析交付状态"]').textContent).toContain('无需 Python');
});
it('does not present an archived input-required report as completed analysis',async()=>{
  await mount({running:false,status:'COMPLETED',events:[],receipt:{resultAvailable:true,visualReport:{status:'NO_DATA',requirementStatus:'INPUT_REQUIRED'}}});
  expect(host.querySelector('[aria-label="分析交付状态"]').textContent).toContain('已归档 · 需补充数据');
});

it('shows live actions and settles the same row when the real completion arrives',async()=>{
  const props=reactive({running:true,status:'EXECUTING',events:[event(1,'TOOL_STARTED')]});
  await mount(props);
  expect(host.textContent).toContain('正在获取授权数据');expect(host.querySelectorAll('tbody tr')).toHaveLength(1);
  expect(host.querySelectorAll('.analysis-event-spinner')).toHaveLength(1);
  props.events.push(event(2,'TOOL_FINISHED'));await settle();
  expect(host.querySelectorAll('tbody tr')).toHaveLength(1);expect(host.textContent).toContain('数据操作已完成');
  expect(host.querySelector('.analysis-event-spinner')).toBeNull();expect(host.textContent).not.toContain('input_read');
  host.querySelectorAll('[role=tab]')[1].click();await settle();
  expect(host.querySelectorAll('details')).toHaveLength(2);expect(host.textContent).toContain('TOOL_STARTED');
});

it('keeps alerts visible, filters issues, and renders technical text as inert data',async()=>{
  const props=reactive({running:true,status:'EXECUTING',events:[event(1,'TOOL_FINISHED'),
    {sequence:2,type:'PERMISSION_DENIED',payload:{errorMessage:'denied'}},
    event(3,'PLAN_READY',{planText:'<img src=x onerror=alert(1)>',modelCall:1})]});
  await mount(props);expect(host.textContent).toContain('权限请求被拒绝');
  const filter=host.querySelector('input[type=checkbox]');filter.checked=true;filter.dispatchEvent(new Event('change'));await settle();
  expect(host.querySelectorAll('tbody tr')).toHaveLength(1);expect(host.textContent).not.toContain('数据操作已完成');
  host.querySelectorAll('[role=tab]')[1].click();await settle();
  expect(host.textContent).toContain('onerror=alert(1)');expect(host.querySelector('img')).toBeNull();
});

it('uses persisted task boundaries for elapsed time and freezes at completion',async()=>{
  vi.useFakeTimers();vi.setSystemTime(4000);
  const props=reactive({running:true,status:'RUNNING',startedAt:new Date(1000).toISOString(),events:[event(1,'TOOL_STARTED')]});
  await mount(props);expect(host.querySelector('time').textContent).toContain('00:03');
  props.status='SUCCEEDED';props.running=false;props.finishedAt=new Date(5000).toISOString();await settle();
  await vi.advanceTimersByTimeAsync(10000);expect(host.querySelector('time').textContent).toContain('00:04');
});
