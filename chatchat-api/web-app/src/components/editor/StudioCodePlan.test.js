// @vitest-environment jsdom
import {describe,it,expect} from 'vitest';
import {createApp,nextTick,reactive,h} from 'vue';
import StudioCodePlan from './StudioCodePlan.vue';
describe('Code plan presentation',()=>{
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
