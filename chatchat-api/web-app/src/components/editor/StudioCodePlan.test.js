// @vitest-environment jsdom
import {describe,it,expect} from 'vitest';
import {createApp,nextTick,reactive,h} from 'vue';
import StudioCodePlan from './StudioCodePlan.vue';
describe('Code plan presentation',()=>{
  it('uses product language when rendering a previously saved technical plan without changing its payload',()=>{
    const plan={intent:{goal:'将授权代码任务交付 Cline，由 Cline 制定分析与生成方案'},
      context:{missing_info:['Runtime OS 尚未收到资料']},
      plan:{steps:[{id:1,input:{description:'Cline 根据授权上下文生成代码'}}]}};
    const original=JSON.stringify(plan),host=document.createElement('div');
    const app=createApp({render:()=>h(StudioCodePlan,{plan})});app.mount(host);
    expect(host.textContent).toContain('任务处理计划');
    expect(host.textContent).toContain('AI 助手');
    expect(host.textContent).toContain('系统 尚未收到资料');
    expect(host.textContent).not.toMatch(/Cline|Runtime/i);
    expect(JSON.stringify(plan)).toBe(original);
    app.unmount();
  });
  it('shows waiting, accepted plan, missing context and failure without inventing completed steps',async()=>{
    const props=reactive({state:'planning',plan:null});const host=document.createElement('div');
    const app=createApp({render:()=>h(StudioCodePlan,props)});app.mount(host);
    expect(host.textContent).toContain('尚未开始探索');
    props.plan={intent:{goal:'解释输入边界'},context:{missing_info:['尚未提供运行报错']},plan:{steps:[{id:1,input:{description:'检查空值分支'}}]}};
    props.state='exploring';await nextTick();
    expect(host.textContent).toContain('解释输入边界');expect(host.textContent).toContain('检查空值分支');expect(host.textContent).toContain('待补充信息');
    expect(host.textContent).toContain('计划不代表已执行或验证');
    props.state='failed';await nextTick();expect(host.textContent).toContain('后续处理未完成');
    app.unmount();
  });
});
