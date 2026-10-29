// @vitest-environment jsdom
import { createApp, h, nextTick, reactive } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import SqlAssistantPanel from './SqlAssistantPanel.vue';
let app, host;
afterEach(()=>{app?.unmount();host?.remove();});
async function mount(values={},slots={}) {
  const props=reactive({models:[],agents:[],projectFiles:[],references:[],referenceIds:[],generations:[],messages:[],prompt:'',aiAction:'generate',pendingPhase:'',busy:false,...values});
  host=document.createElement('div');document.body.append(host);
  app=createApp({render:()=>h(SqlAssistantPanel,props,slots)});app.mount(host);await nextTick();return props;
}
describe('SQL assistant conversation',()=>{
  it('releases the native modal before opening document selection through the context slot',async()=>{
    const openLibrary=vi.fn();
    await mount({}, {'context-documents':({closeSettings})=>h('button',{id:'library-launch',onClick:()=>{closeSettings();openLibrary();}},'Choose document')});
    host.querySelector('[aria-label="高级设置"]').click();
    expect(document.querySelector('[aria-label="AI 助手高级设置"]').open).toBe(true);
    document.querySelector('#library-launch').click();await nextTick();
    expect(openLibrary).toHaveBeenCalledOnce();expect(document.querySelector('[aria-label="AI 助手高级设置"]').open).toBe(false);
  });
  it('never inserts SQL from failed or unchecked Markdown notes',async()=>{
    const props=await mount({editorEditable:true,aiAction:'explore',messages:[{id:'a',role:'assistant',generationId:'g',text:'```sql\nSELECT hidden FROM private\n```'}],
      result:{generation:{id:'g',prepared:{taskRequest:{toolInput:{sqlStudioAction:'explore'}}}},task:{status:'SUCCESS'},files:[],events:[],rawAnswer:'',metadataExploration:{fieldValidation:'NOT_RUN'}}});
    expect(host.querySelector('[title="插入编辑器"]')).toBeNull();
    const insert=()=>[...host.querySelectorAll('button')].find(b=>b.textContent==='插入编辑器');
    expect(insert().disabled).toBe(true);
    props.result.metadataExploration.fieldValidation='CHECKED';await nextTick();expect(insert().disabled).toBe(false);
    props.result.outputError='damaged output';await nextTick();expect(insert().disabled).toBe(true);
  });
  it('pins context above conversation and input below it without the old form or template tab',async()=>{
    const prompt=vi.fn(),action=vi.fn();await mount({onPrompt:prompt,onAction:action},{'database-context':()=>h('div',{id:'source-toolbar'},'Shared context')});
    expect(host.querySelector('aside>header #source-toolbar')).not.toBeNull();expect(host.querySelector('.sql-assistant-scroll #source-toolbar')).toBeNull();
    expect(host.querySelector('aside>footer textarea')).not.toBeNull();expect(host.querySelector('.sql-assistant-workbench,.sql-current-context')).toBeNull();
    host.querySelector('.sql-chat-empty-state button').click();await nextTick();
    expect(prompt).toHaveBeenCalledWith('探索当前数据库有哪些业务表');expect(action).toHaveBeenCalledWith('explore');
    expect(document.querySelector('[aria-label="AI 助手高级设置"]').open).toBe(false);
  });
  it('opens settings and real generation history outside the conversation flow',async()=>{
    const generation=vi.fn();await mount({generations:[{id:'actual-history',prepared:{taskRequest:{toolInput:{studioUserRequest:'Saved question'}}}}],onGeneration:generation});
    host.querySelector('[aria-label="高级设置"]').click();expect(document.querySelector('[aria-label="AI 助手高级设置"]').open).toBe(true);
    document.querySelector('[aria-label="关闭高级设置"]').click();expect(document.querySelector('[aria-label="AI 助手高级设置"]').open).toBe(false);
    host.querySelector('[aria-label="历史会话"]').click();document.querySelector('.sql-assistant-history button').click();await nextTick();
    expect(generation).toHaveBeenCalledWith('actual-history');expect(document.querySelector('[aria-label="SQL 历史会话"]').open).toBe(false);
  });
  it('uses multiline Enter, Ctrl/Cmd Enter send, composition protection and source-ready button state',async()=>{
    const send=vi.fn();const props=await mount({aiAction:'explore',contextReady:false,prompt:'Find tables',onSend:send});const textarea=host.querySelector('textarea');
    expect(host.querySelector('.sql-send-question').disabled).toBe(true);props.contextReady=true;await nextTick();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));expect(send).not.toHaveBeenCalled();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',ctrlKey:true,isComposing:true,bubbles:true}));expect(send).not.toHaveBeenCalled();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',ctrlKey:true,bubbles:true}));expect(send).toHaveBeenCalledOnce();
    textarea.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',metaKey:true,bubbles:true}));expect(send).toHaveBeenCalledTimes(2);
    props.taskRunning=true;await nextTick();expect(host.querySelector('.sql-send-question')).toBeNull();expect(host.querySelector('.sql-stop-question')).not.toBeNull();
  });
  it('keeps mode inside the input controls and stops only through the existing real task action',async()=>{
    const cancel=vi.fn();await mount({taskRunning:true,onCancel:cancel,result:{generation:{id:'g'},task:{taskId:'real-task',status:'RUNNING'},files:[],events:[],rawAnswer:''}});
    expect(host.querySelector('.sql-composer-actions [aria-label="SQL 助手方式"]')).not.toBeNull();
    host.querySelector('[aria-label="停止任务"]').click();expect(cancel).toHaveBeenCalledOnce();
    expect(host.querySelector('.sql-progress-spinner')).not.toBeNull();
  });
  it('retries the actual question only after terminal failure and never treats a repairing attempt as failed',async()=>{
    const retry=vi.fn();const props=await mount({aiAction:'explore',onRetry:retry,messages:[{id:'u',role:'user',text:'Find actual scheduling tables'},
      {id:'a',role:'assistant',generationId:'g',status:'failed',text:'Old failure'}],result:{generation:{id:'g'},task:{status:'FAILED',canonicalState:'REPAIRING'},files:[],events:[],rawAnswer:''}});
    expect(host.querySelector('.sql-retry-question')).toBeNull();props.result.task.canonicalState='FAILED';await nextTick();
    host.querySelector('.sql-retry-question').click();expect(retry).toHaveBeenCalledWith({question:'Find actual scheduling tables',action:'explore'});
  });
  it('restores a real saved question and renders Markdown without executing model HTML',async()=>{
    await mount({aiAction:'explore',result:{generation:{id:'saved',prepared:{taskRequest:{toolInput:{sqlStudioAction:'explore',studioUserRequest:'哪些表可用于调度分析？'}}}},task:{status:'SUCCESS',canonicalState:'SUCCEEDED'},files:[],events:[],
      rawAnswer:JSON.stringify({notes:'| 表 | 依据 |\n| --- | --- |\n| jobs | 实际字段 |\n\n<script>bad()</script><img src=x onerror=bad()>'})}});
    expect(host.querySelector('.sql-chat-turn.user').textContent).toContain('哪些表可用于调度分析');expect(host.querySelector('.sql-ai-markdown table').textContent).toContain('jobs');
    expect(host.querySelector('.sql-ai-markdown script,.sql-ai-markdown img')).toBeNull();
  });
  it('keeps loading during canonical repair and hides the predecessor error until a real terminal state',async()=>{
    const props=await mount({aiAction:'explore',result:{generation:{id:'repair',prepared:{taskRequest:{toolInput:{sqlStudioAction:'explore'}}}},task:{taskId:'attempt',status:'FAILED',canonicalState:'REPAIRING',errorMessage:'Old attempt failed'},files:[],events:[],rawAnswer:''}});
    expect(host.querySelector('.sql-progress-spinner')).not.toBeNull();expect(host.querySelector('.sql-chat-result .app-notice')).toBeNull();
    props.result.task.canonicalState='FAILED';await nextTick();expect(host.querySelector('.sql-progress-spinner')).toBeNull();expect(host.querySelector('.sql-chat-result').textContent).toContain('Old attempt failed');
  });
  it('shows selected user documents and their actual purpose instead of a legacy case count',async()=>{
    await mount({mappingCount:0,userDocuments:[{fileId:'rules',fileName:'rules.md',purpose:'RULES'},{fileId:'facts',fileName:'facts.json',purpose:'EVIDENCE'}]});
    const context=document.querySelector('.sql-chat-context');
    expect(context.querySelector('.sql-chat-settings').textContent).toContain('参考规则');expect(context.querySelector('.sql-chat-settings').textContent).toContain('分析依据');
    expect(host.querySelector('.sql-ai-modes')).toBeNull();expect(host.querySelector('.sql-composer-tools')).toBeNull();
    expect(document.querySelector('[aria-label="AI 助手高级设置"]').open).toBe(false);
    expect(context.textContent).not.toContain('案例');expect(context.textContent).not.toContain('????');
  });
  it('hides Agent execution without usable published options and filters out draft options',async()=>{
    const props=await mount({executionMode:'agent',agents:[{id:'draft',name:'Draft',marketStatus:'draft'}]});
    expect(document.querySelector('.sql-execution-modes')).toBeNull();
    expect(host.querySelector('select').textContent).not.toContain('Draft');
    props.agents=[{id:'published',name:'Published',marketStatus:'published'},{id:'draft',name:'Draft',marketStatus:'draft'}];await nextTick();
    expect(document.querySelector('.sql-execution-modes')).not.toBeNull();
    const choices=[...document.querySelector('.sql-agent-choice select').options].map(option=>option.value);
    expect(choices).toEqual(['','published']);expect(document.querySelector('.sql-agent-choice option[value=""]').disabled).toBe(true);
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
    expect(reply.querySelector('[aria-label="实际执行进展"]').textContent).toContain('3/5 准备数据');
    expect(reply.querySelector('[aria-label="实际执行进展"]').textContent).not.toContain('schema_lookup');
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
