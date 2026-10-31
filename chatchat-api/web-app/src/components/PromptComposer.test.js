// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from "vue";
import { it, expect, afterEach, beforeEach, vi } from "vitest";
import PromptComposer from "./PromptComposer.vue";
import {setWorkspaceContext} from '../js/utils/workspaceContext';
let app, host;
const settleIdeas=async()=>{for(let i=0;i<6;i++)await nextTick();};
it('keeps pasted source text intact when the model suggests a differently worded question',async()=>{
 const original='  date,assets\n2026-07-31,12200\n原始补充口径\n';
 host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,{compact:true,showIdea:true,modelValue:original,ideaProvider:async()=>({ideas:[{title:'核对资产',prompt:'请核对期末资产与净流入之间的差异。'}]})})});app.mount(host);
 host.querySelector('[aria-label="提问灵感"]').click();await settleIdeas();host.querySelector('.composer-idea-option').click();await settleIdeas();
 expect(host.querySelector('textarea').value).toBe(original+'\n\n分析问题：\n请核对期末资产与净流入之间的差异。');
});
it('generates suggestions asynchronously and ignores results after the draft changes',async()=>{
 let finish;const provider=vi.fn(()=>new Promise(resolve=>{finish=resolve;}));
 const props=reactive({compact:true,showIdea:true,modelValue:'当前盈亏差异',selectedAgentId:'authorized',ideaProvider:provider});
 host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,props)});app.mount(host);
 host.querySelector('[aria-label="提问灵感"]').click();await settleIdeas();
 expect(host.querySelector('[role=status]').textContent).toContain('正在');expect(host.querySelector('.composer-idea-option')).toBeNull();expect(provider).toHaveBeenCalledWith({draft:'当前盈亏差异',agentId:'authorized'});
 props.modelValue='补充新的期间';await settleIdeas();finish({ideas:[{title:'过期建议',prompt:'不应显示'}]});await settleIdeas();
 expect(host.querySelector('[aria-label="提问建议"]')).toBeNull();expect(host.querySelector('textarea').value).toBe('补充新的期间');
});
it('shows generation failures without fabricated fallback suggestions and can retry',async()=>{
 const provider=vi.fn().mockRejectedValueOnce(new Error('模型暂不可用')).mockResolvedValueOnce({ideas:[{title:'核对资金变动',prompt:'结合补充资料核对期末资产与净流入的差异'}]});
 host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,{compact:true,showIdea:true,ideaProvider:provider})});app.mount(host);
 host.querySelector('[aria-label="提问灵感"]').click();await settleIdeas();expect(host.querySelector('[role=alert]').textContent).toContain('模型暂不可用');expect(host.querySelector('.composer-idea-option')).toBeNull();
 host.querySelector('.composer-idea-refresh').click();await settleIdeas();expect(provider).toHaveBeenCalledTimes(2);expect(host.querySelector('.composer-idea-option').textContent).toContain('核对资金变动');
});
beforeEach(()=>{const storage=new Map();vi.stubGlobal('localStorage',{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value)),clear:()=>storage.clear()});});
afterEach(() => {
  app?.unmount();
  host?.remove();
  setWorkspaceContext(null);localStorage.clear();
  vi.unstubAllGlobals();
});
it('persists adaptive layout preferences in the existing user/workspace scope and sends a snapshot',async()=>{
  const scope={tenantId:'tenant',userId:'owner',workspaceId:'zhice'};setWorkspaceContext(scope);
  host=document.createElement('div');document.body.append(host);const sends=[];
  const mount=()=>{app=createApp({render:()=>h(PromptComposer,{modelValue:'Analyze data',onSend:value=>sends.push(value)})});app.mount(host);};
  mount();host.querySelector('[role=switch]').click();await nextTick();
  const strategy=host.querySelector('[aria-label="报告排版策略"]');expect(strategy.value).toBe('auto');
  strategy.value='concise';strategy.dispatchEvent(new Event('change',{bubbles:true}));await nextTick();
  host.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await nextTick();
  expect(sends[0].reportPresentation).toMatchObject({strategy:'concise',autoCharts:true,autoImages:true});
  app.unmount();mount();host.querySelector('[role=switch]').click();await nextTick();
  expect(host.querySelector('[aria-label="报告排版策略"]').value).toBe('concise');
  app.unmount();setWorkspaceContext({...scope,userId:'another-owner'});mount();host.querySelector('[role=switch]').click();await nextTick();
  expect(host.querySelector('[aria-label="报告排版策略"]').value).toBe('auto');
});
it("keeps reports selected across sends and conversation changes until manually disabled", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const sends = [],
    props = reactive({ modelValue: "Analyze this dataset", conversationKey: "one" });
  app = createApp({
    render: () => h(PromptComposer, { ...props, onSend: (value) => sends.push(value) }),
  });
  app.mount(host);
  await nextTick();
  const toggle = host.querySelector("[role=switch]");
  expect(toggle.textContent).toContain("图文报告");
  expect(toggle.getAttribute("aria-checked")).toBe("false");
  toggle.click();
  await nextTick();
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[0].reportMode).toBe("visual");
  expect(toggle.getAttribute("aria-checked")).toBe("true");
  expect(sends[0].documentWorkflow).toBe(false);
  expect(sends[0]).not.toHaveProperty("webSearch");
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[1].reportMode).toBe("visual");
  props.conversationKey = "two";
  await nextTick();
  expect(toggle.getAttribute("aria-checked")).toBe("true");
  expect(toggle.classList.contains("active")).toBe(true);
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[2].reportMode).toBe("visual");
  toggle.click();
  await nextTick();
  expect(toggle.getAttribute("aria-checked")).toBe("false");
  expect(toggle.classList.contains("active")).toBe(false);
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[3].reportMode).toBe("off");
});

it("preserves the selection during execution alongside the document workflow badge", async () => {
  host = document.createElement("div");
  document.body.append(host);
  const sends = [];
  const props = reactive({
    modelValue: "Analyze documents",
    loading: false,
    selectedAgentId: "document-agent",
    agents: [
      {
        id: "document-agent",
        name: "Document agent",
        marketStatus: "published",
        boundDocumentCount: 1,
      },
    ],
  });
  app = createApp({
    render: () => h(PromptComposer, { ...props, onSend: (value) => sends.push(value) }),
  });
  app.mount(host);
  await nextTick();
  const toggle = host.querySelector('[role="switch"]');
  expect(host.querySelector(".workflow-badge").textContent.trim()).toBe("文档工作流");
  toggle.click();
  await nextTick();
  props.loading = true;
  await nextTick();
  expect(toggle.disabled).toBe(true);
  toggle.click();
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends).toHaveLength(0);
  expect(toggle.getAttribute("aria-checked")).toBe("true");
  props.loading = false;
  await nextTick();
  host
    .querySelector("form")
    .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  await nextTick();
  expect(sends[0]).toMatchObject({ reportMode: "visual", documentWorkflow: true });
  expect(toggle.getAttribute("aria-checked")).toBe("true");
});

it('uses compact input with inherited-data disabling while preserving send behavior',async()=>{
 const props=reactive({compact:true,inputDisabled:true,modelValue:'继续分析',placeholder:'继续提问'}),sends=[];host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,{...props,onSend:v=>sends.push(v)})});app.mount(host);await nextTick();expect(host.querySelector('[role=switch]')).toBeNull();expect(host.querySelector('textarea').disabled).toBe(true);host.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));expect(sends).toHaveLength(0);props.inputDisabled=false;await nextTick();host.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));expect(sends[0].query).toBe('继续分析');
});

it('puts Agent selection below the input and only fills an editable draft when an idea is picked',async()=>{
 const props=reactive({compact:true,showAgentPicker:true,showIdea:true,agentRequired:true,modelValue:'核对本期交易',ideaProvider:async()=>({ideas:[{title:'核对交易',prompt:'核对本期交易并明确比较基准'}]}),agents:[],ideaContext:{hasResult:true,title:'客户资产'}}),sends=[];
 host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(PromptComposer,{...props,onSend:v=>sends.push(v)})});app.mount(host);await nextTick();
 expect(host.querySelector('form [aria-label="选择 Agent"]')).toBeNull();expect(host.querySelector('.composer-agent-footer select')).not.toBeNull();
 expect(host.querySelector('textarea').disabled).toBe(false);expect(host.querySelector('[type=submit]').disabled).toBe(true);
 host.querySelector('[aria-label="提问灵感"]').click();await settleIdeas();expect(host.querySelector('[aria-label="提问建议"]')).not.toBeNull();
 host.querySelector('.composer-idea-option').click();await nextTick();expect(host.querySelector('textarea').value).toContain('核对本期交易');expect(host.querySelector('textarea').value).toContain('比较基准');
 expect(sends).toHaveLength(0);expect(host.querySelector('[aria-label="提问建议"]')).toBeNull();
});
it('closes stale ideas when the analysis changes and never truncates an overlong draft',async()=>{
 const props=reactive({compact:true,showIdea:true,modelValue:'原问题',ideaProvider:async()=>({ideas:[{title:'细化问题',prompt:'原问题与比较基准及其变动原因'}]}),maxLength:5,ideaContext:{title:'原分析'}});
 host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(PromptComposer,props)});app.mount(host);await nextTick();
 host.querySelector('[aria-label="提问灵感"]').click();await settleIdeas();host.querySelector('.composer-idea-option').click();await nextTick();
 expect(host.querySelector('textarea').value).toBe('原问题');expect(host.querySelector('[role=alert]').textContent).toContain('超过');
 props.ideaContext={title:'新分析'};await nextTick();expect(host.querySelector('[aria-label="提问建议"]')).toBeNull();
});
it('keeps sending disabled for a stale Agent id while allowing question drafting and ideas',async()=>{
 const sends=[];host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,{compact:true,showIdea:true,agentRequired:true,selectedAgentId:'revoked',agents:[{id:'available',name:'已发布',marketStatus:'published'}],modelValue:'我的问题',onSend:v=>sends.push(v)})});app.mount(host);await nextTick();
 expect(host.querySelector('textarea').disabled).toBe(false);expect(host.querySelector('[aria-label="提问灵感"]').disabled).toBe(false);expect(host.querySelector('[type=submit]').disabled).toBe(true);
 host.querySelector('form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));expect(sends).toHaveLength(0);
});

it('offers only published agents in compact mode and requires a selection before sending',async()=>{const props=reactive({compact:true,showAgentPicker:true,agentRequired:true,modelValue:'分析资产',selectedAgentId:'',agents:[{id:'a',name:'业务分析',marketStatus:'published'},{id:'b',name:'未发布',marketStatus:'draft'}]}),sends=[],changes=[];host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,{...props,onSend:v=>sends.push(v),'onUpdate:selectedAgentId':v=>changes.push(v)})});app.mount(host);await nextTick();const picker=host.querySelector('[aria-label="选择 Agent"]');expect([...picker.options].map(o=>o.value)).toEqual(['','a']);expect(host.querySelector('[type=submit]').disabled).toBe(true);picker.value='a';picker.dispatchEvent(new Event('change'));expect(changes).toEqual(['a']);props.selectedAgentId='a';await nextTick();host.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));expect(sends[0].agentId).toBe('a');expect(host.querySelector('[role=switch]')).toBeNull();});
