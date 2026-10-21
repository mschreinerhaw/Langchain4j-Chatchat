// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from 'vue';
import { afterEach, describe, expect, it } from 'vitest';
import SqlAssistantPanel from './SqlAssistantPanel.vue';
let app, host;
afterEach(()=>{app?.unmount();host?.remove();});
async function mount(values={}) {
  const props=reactive({models:[],agents:[],projectFiles:[],references:[],referenceIds:[],generations:[],messages:[],prompt:'',aiAction:'generate',pendingPhase:'',busy:false,...values});
  host=document.createElement('div');document.body.append(host);
  app=createApp({render:()=>h(SqlAssistantPanel,props)});app.mount(host);await nextTick();return props;
}
describe('SQL assistant conversation',()=>{
  it('shows selected user documents and their actual purpose instead of a legacy case count',async()=>{
    await mount({mappingCount:0,userDocuments:[{fileId:'rules',fileName:'rules.md',purpose:'RULES'},{fileId:'facts',fileName:'facts.json',purpose:'EVIDENCE'}]});
    const context=host.querySelector('.sql-chat-context');
    expect(context.textContent).toContain('2 文档');expect(context.textContent).toContain('参考规则');expect(context.textContent).toContain('分析依据');
    expect(context.textContent).not.toContain('案例');expect(context.textContent).not.toContain('????');
  });
  it('shows pending planning inside the assistant reply without an overlay or fabricated plan',async()=>{
    const props=await mount({pendingPhase:'PREPARING',pendingStartedAt:Date.now(),busy:true,
      messages:[{id:'user:r',role:'user',text:'生成 ETL'},{id:'assistant:r',role:'assistant',text:'等待计划',status:'planning'}]});
    expect(host.querySelector('.sql-assistant-panel > .sql-assistant-progress')).toBeNull();
    expect(host.querySelector('.sql-chat-turn.assistant .sql-assistant-progress').textContent).toContain('正在制定代码协作计划');
    expect(host.querySelector('.studio-code-plan')).toBeNull();
    props.pendingPhase='';props.busy=false;props.messages[1].status='failed';props.messages[1].text='计划请求超时：studio-code-request-1';await nextTick();
    expect(host.querySelector('.sql-chat-turn.assistant').textContent).toContain('studio-code-request-1');
  });
  it('keeps earlier replies and puts actual Runtime progress and candidate files in the current reply',async()=>{
    const props=await mount({messages:[{id:'old',role:'assistant',generationId:'previous',text:'上轮解释保留'},
      {id:'user:g',role:'user',text:'修复 SQL'},{id:'assistant:g',role:'assistant',generationId:'g',text:'分析中'}],
      result:{generation:{id:'g',applied:false,prepared:{taskRequest:{toolInput:{sqlStudioAction:'fix'}}}},
        task:{status:'RUNNING'},events:[{eventId:'e1',sequence:1,type:'TOOL_CALL_STARTED',toolName:'schema_lookup'}],files:[],rawAnswer:''}});
    expect(host.querySelectorAll('.sql-chat-turn.assistant')).toHaveLength(2);
    expect(host.querySelector('.sql-chat-turn.assistant').textContent).toContain('上轮解释保留');
    const reply=host.querySelectorAll('.sql-chat-turn.assistant')[1];
    expect(reply.querySelector('[aria-label="实际执行进展"]').textContent).toContain('schema_lookup');
    props.result={...props.result,task:{status:'SUCCESS'},files:[{path:'sql/fix.sql',content:'SELECT 1;'}],rawAnswer:'{"notes":"已生成候选代码，未执行 SQL"}'};await nextTick();
    expect(reply.querySelector('.sql-chat-result').textContent).toContain('审查 1 个文件变更');
    expect(host.querySelector('.sql-assistant-scroll > .sql-chat-result')).toBeNull();
  });
  it('wraps a restored historical generation in a reply without inventing a user message',async()=>{
    await mount({result:{generation:{id:'saved',applied:false},task:{status:'SUCCESS'},files:[],events:[],rawAnswer:'{"notes":"历史回答"}'}});
    expect(host.querySelectorAll('.sql-chat-turn.user')).toHaveLength(0);
    expect(host.querySelector('.sql-chat-turn.assistant').textContent).toContain('历史回答');
  });
});
