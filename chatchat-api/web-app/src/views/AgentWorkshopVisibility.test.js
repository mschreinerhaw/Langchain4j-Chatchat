// @vitest-environment jsdom
import {createApp,nextTick} from 'vue';
import {it,expect,vi,afterEach} from 'vitest';
import AgentWorkshopView from './AgentWorkshopView.vue';
import {fetchAgentWorkshop} from '../services/api.js';
vi.mock('../services/api.js',()=>({fetchAgentWorkshop:vi.fn(),createWorkshopAgent:vi.fn(),deleteWorkshopAgent:vi.fn(),fetchPublishedAgentCurlExample:vi.fn(),getStoredAuthSession:()=>({user:{permissionCodes:['system:agent-registry:manage']}}),publishWorkshopAgent:vi.fn(),recallWorkshopAgent:vi.fn(),setDefaultWorkshopAgent:vi.fn(),updateWorkshopAgent:vi.fn()}));
let app,root;
afterEach(()=>{app?.unmount();root?.remove();vi.clearAllMocks();});
async function mount(agentCount,agents,total=agentCount){
  fetchAgentWorkshop.mockResolvedValue({summary:{agentCount},agents,page:{total,page:1,pageSize:6,totalPages:Math.max(1,Math.ceil(total/6))}});
  root=document.createElement('div');document.body.append(root);app=createApp(AgentWorkshopView);app.mount(root);await new Promise(resolve=>setTimeout(resolve,0));await nextTick();
}
it('renders all returned published and unpublished cards when administrator total is nonzero',async()=>{
  const agents=Array.from({length:6},(_,index)=>({id:'agent-'+index,name:'查询服务 '+index,marketStatus:index%2?'published':'draft'}));
  await mount(21,agents);
  expect(root.querySelectorAll('.agent-card')).toHaveLength(6);
  expect(root.textContent).toContain('查询服务 0');expect(root.textContent).toContain('查询服务 1');
  expect(root.textContent).not.toContain('暂无Agent配置');expect(root.textContent).toContain('21 / 21');
});
it('shows no configuration only when the authorized catalog is empty',async()=>{
  await mount(0,[]);expect(root.querySelectorAll('.agent-card')).toHaveLength(0);expect(root.textContent).toContain('暂无Agent配置');
});
it('distinguishes an empty filter result from an empty catalog',async()=>{
  await mount(21,[],0);expect(root.textContent).toContain('没有匹配的Agent');expect(root.textContent).not.toContain('暂无Agent配置');
});
