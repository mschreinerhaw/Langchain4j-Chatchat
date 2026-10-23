// @vitest-environment jsdom
import { createApp, nextTick } from "vue";
import { it, expect, vi, beforeEach, afterEach } from "vitest";
import ToolPublicationsView from "./ToolPublicationsView.vue";
import { apiRequest } from "../services/api.js";
vi.mock("../services/api.js",()=>({apiRequest:vi.fn(),getStoredAuthSession:()=>({user:{permissionCodes:["mcp:publication:edit","mcp:publication:publish"]}})}));
const definition={domainCode:"business",parentToolName:"ssh_template_query",roleId:"role",subjectType:"ROLE",templateKeys:["authorized"],enabled:true};
const release=(id,version,state)=>({id,logicalId:"logical",title:"业务工具",description:"受控分析",releaseNumber:version,state,definitionJson:JSON.stringify(definition),inputSchemaJson:'{"type":"object"}',outputSchemaJson:'{"type":"object"}',publishedAt:state==="RUNNING"?"2026-10-23T00:00:00Z":null});
let app,root;
const flush=async()=>{await new Promise(r=>setTimeout(r,0));await nextTick();};
beforeEach(()=>{vi.resetAllMocks();apiRequest.mockImplementation(async(path,options)=>{
 if(path==="/tool-publications")return options?.method==="POST"?release("new",3,"DRAFT"):[release("draft",2,"DRAFT"),release("live",1,"RUNNING")];
 if(path.endsWith("/parents"))return [{toolName:"ssh_template_query",title:"主机模板"}];
 if(path.endsWith("/roles"))return [{id:"role",roleName:"业务角色"}];
 if(path.includes("/templates?"))return [{key:"authorized",title:"已授权模板"}];
 return {};
});});
afterEach(()=>{app?.unmount();root?.remove();});
async function mount(){root=document.createElement("div");document.body.append(root);app=createApp(ToolPublicationsView);const vm=app.mount(root);await flush();return vm;}
it("groups versions into one business tool while preserving version selection and state filters",async()=>{
 const vm=await mount();expect(vm.stateFilter).toBe("RUNNING");expect(vm.tools).toHaveLength(1);expect(vm.selectedId).toBe("live");expect(vm.selected.state).toBe("RUNNING");expect(vm.versions.map(r=>r.id)).toEqual(["draft","live"]);vm.stateFilter="";expect(vm.tools[0].id).toBe("draft");vm.stateFilter="RUNNING";expect(vm.tools[0].id).toBe("live");vm.query="不存在";await nextTick();expect(vm.visibleTools).toHaveLength(0);expect(root.textContent).toContain("没有匹配的工具");
});
it("shows an empty running list without silently selecting a draft",async()=>{
 const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>path==="/tool-publications"?Promise.resolve([release("draft",2,"DRAFT")]):original(path,options));
 const vm=await mount();expect(vm.tools).toHaveLength(0);expect(vm.selected).toBeNull();vm.stateFilter="DRAFT";expect(vm.tools[0].id).toBe("draft");
});
it("reveals the saved draft even when the initial filter is running",async()=>{
 const vm=await mount();const original=apiRequest.getMockImplementation();apiRequest.mockImplementation((path,options)=>path==="/tool-publications"&&!options?.method?Promise.resolve([release("new",3,"DRAFT"),...vm.rows]):original(path,options));
 vm.reset();vm.form.title="新工具";vm.definition={...definition,enabled:false};vm.templates=[{key:"authorized",title:"已授权模板"}];await vm.save();expect(vm.failed).toBe(false);expect(vm.stateFilter).toBe("DRAFT");expect(vm.selectedId).toBe("new");expect(vm.tools.some(row=>row.id==="new")).toBe(true);
});
it("keeps the draft when schema validation fails without issuing a write",async()=>{
 const vm=await mount();vm.reset();vm.form.title="新工具";vm.definition={...definition,enabled:false};vm.templates=[{key:"authorized",title:"已授权模板"}];vm.inputSchema="invalid";await vm.save();expect(vm.failed).toBe(true);expect(vm.notice).toContain("有效 JSON");expect(vm.editing).toBe(true);expect(apiRequest.mock.calls.some(c=>c[1]?.method==="POST")).toBe(false);
});
it("creates a new revision without modifying the immutable source and fully resets a fresh tool",async()=>{
 const vm=await mount();vm.choose(vm.rows[1]);await vm.startRevision();vm.form.title="新版";vm.definition.templateKeys.push("other");expect(JSON.parse(vm.rows[1].definitionJson).templateKeys).toEqual(["authorized"]);vi.spyOn(window,"confirm").mockReturnValue(true);vm.inputSample='{"old":true}';vm.inputSchema='{"old":true}';vm.reset();expect(vm.form.logicalId).toBeNull();expect(vm.inputSample).toBe("{}");expect(JSON.parse(vm.inputSchema)).toEqual({type:"object",properties:{}});vi.restoreAllMocks();
});
it("uses the selected immutable release id and actual test input for lifecycle commands",async()=>{
 const vm=await mount();vm.choose(vm.rows[1]);vm.inputSample='{"limit":1}';await vm.test();const call=apiRequest.mock.calls.find(c=>c[0]==="/tool-publications/live/test");expect(JSON.parse(call[1].body)).toEqual({input:{limit:1}});expect(vm.selectedId).toBe("live");
});
it("ignores stale template responses when the role changes",async()=>{
 const vm=await mount();let finish;apiRequest.mockImplementationOnce(()=>new Promise(r=>{finish=r;})).mockResolvedValueOnce([{key:"new",title:"新角色模板"}]);vm.definition={...definition,templateKeys:["authorized"]};const first=vm.loadTemplates();vm.definition.roleId="next";await vm.loadTemplates();finish([{key:"old",title:"旧角色模板"}]);await first;expect(vm.templates.map(t=>t.key)).toEqual(["new"]);expect(vm.definition.templateKeys).toEqual([]);
});
