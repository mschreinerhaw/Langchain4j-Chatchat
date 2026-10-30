// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from "vue";
import { it, expect, afterEach, beforeEach, vi } from "vitest";
import PromptComposer from "./PromptComposer.vue";
import {setWorkspaceContext} from '../js/utils/workspaceContext';
let app, host;
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

it('offers only published agents in compact mode and requires a selection before sending',async()=>{const props=reactive({compact:true,showAgentPicker:true,agentRequired:true,modelValue:'分析资产',selectedAgentId:'',agents:[{id:'a',name:'业务分析',marketStatus:'published'},{id:'b',name:'未发布',marketStatus:'draft'}]}),sends=[],changes=[];host=document.createElement('div');app=createApp({render:()=>h(PromptComposer,{...props,onSend:v=>sends.push(v),'onUpdate:selectedAgentId':v=>changes.push(v)})});app.mount(host);await nextTick();const picker=host.querySelector('[aria-label="选择 Agent"]');expect([...picker.options].map(o=>o.value)).toEqual(['','a']);expect(host.querySelector('[type=submit]').disabled).toBe(true);picker.value='a';picker.dispatchEvent(new Event('change'));expect(changes).toEqual(['a']);props.selectedAgentId='a';await nextTick();host.querySelector('form').dispatchEvent(new Event('submit',{cancelable:true}));expect(sends[0].agentId).toBe('a');expect(host.querySelector('[role=switch]')).toBeNull();});
