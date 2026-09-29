// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, nextTick } from "vue";
import Panel from "../../views/SkillRoleAuthorizationPanel.vue";
const api = vi.hoisted(() => ({ fetchSkillRoleQuery: vi.fn() }));
vi.mock("../../services/api", () => api);
let app;
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); };
const result = (items, total = items.length, page = 1) => ({ items, total, page, pageSize: 20, totalPages: Math.max(1, Math.ceil(total/20)) });
beforeEach(() => {
  vi.resetAllMocks();
  api.fetchSkillRoleQuery.mockImplementation(async params => {
    if (params.view === "roles") return result(Array.from({length:20}, (_, i) => ({id:"r"+i,name:"角色"+i,code:"ROLE"+i})), 100, params.page);
    if (params.view === "skills") return result(Array.from({length:20}, (_, i) => ({id:"s"+i,name:"固收技能"+i,resource_type:"SKILL"})), 1000, params.page);
    return result(Array.from({length:20}, (_, i) => ({role_id:params.roleId || "r"+i,role_name:"角色"+i,role_code:"R"+i,skill_id:params.skillId || "s"+i,skill_name:"技能"+i,resource_type:"SKILL",agent_id:"a",agent_name:"固收 Agent",source:"资源授权"})), 1000, params.page);
  });
});
afterEach(() => { app?.unmount(); document.body.innerHTML = ""; });
async function mount() {
 const root=document.createElement("div");document.body.append(root);
 app=createApp(Panel,{tenantId:"t"}); const vm=app.mount(root);await settle();return {root,vm};
}
it("renders only one page for 100 roles and 1000 relations with two bounded requests", async () => {
 const {root}=await mount();
 expect(root.querySelectorAll(".skill-role-picker-item")).toHaveLength(20);
 expect(root.querySelectorAll("tbody tr")).toHaveLength(20);
 expect(api.fetchSkillRoleQuery).toHaveBeenCalledTimes(2);
 expect(root.textContent).toContain("共 100 项");
 expect(root.textContent).toContain("共 1000 条有效授权关系");
});
it("loads inverse queries and pages without downloading the full catalog",async()=>{
 const {root,vm}=await mount();vm.selectMode("skill");await settle();
 expect(api.fetchSkillRoleQuery).toHaveBeenCalledWith(expect.objectContaining({view:"relations",skillId:"s0",resourceType:"SKILL",roleId:""}),expect.any(AbortSignal));
 expect(root.querySelectorAll(".skill-role-picker-item")).toHaveLength(20);
 vm.changePage("catalog",2);await settle();
 expect(api.fetchSkillRoleQuery).toHaveBeenCalledWith(expect.objectContaining({view:"skills",page:2,pageSize:20}),expect.any(AbortSignal));
});
it("debounces fuzzy search and resets pagination",async()=>{
 const {vm}=await mount();vm.query="固";await nextTick();vm.query="固收";await nextTick();
 await new Promise(resolve=>setTimeout(resolve,330));await settle();
 const queries=api.fetchSkillRoleQuery.mock.calls.filter(([p])=>p.view==="roles" && p.query);
 expect(queries).toHaveLength(1);expect(queries[0][0]).toMatchObject({query:"固收",page:1});
});
it("drops stale relation responses when selection changes",async()=>{
 const {root,vm}=await mount();let release;
 api.fetchSkillRoleQuery.mockImplementation(params => params.roleId==="r1"
   ? new Promise(resolve=>{release=resolve;}) : Promise.resolve(result([])));
 vm.select({id:"r1",name:"旧角色"});await nextTick();vm.select({id:"r2",name:"新角色"});await settle();
 release(result([{role_id:"r1",skill_id:"stale",skill_name:"过期结果",resource_type:"SKILL",source:"资源授权"}]));await settle();
 expect(root.textContent).not.toContain("过期结果");expect(root.textContent).toContain("新角色");
});
it("does not report failed queries as an empty authorization result",async()=>{
 api.fetchSkillRoleQuery.mockRejectedValue(new Error("查询失败"));
 const {root}=await mount();expect(root.querySelector('[role="alert"]').textContent).toContain("查询失败");
 expect(root.querySelector(".skill-role-picker-body").textContent).not.toContain("没有匹配");
});
