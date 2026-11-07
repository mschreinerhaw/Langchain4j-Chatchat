/** General coding profile. Cline Agent owns all reasoning and tool iteration. */
import {Agent} from '@cline/agents';
import {createDefaultTools,createMcpTools} from '@cline/sdk';
import {resolveToolRoutingConfig,DEFAULT_MODEL_TOOL_ROUTING_RULES} from './cline-tool-routing.js';
import {tmpdir} from 'node:os';
import {relative,resolve,isAbsolute} from 'node:path';
import {createNativeCodeWorkspace} from './native-workspace.js';
import {createNativeSession} from './native-session.js';
import {sdkPrepareTurn,completeToolBoundary} from './sdk-adapter.js';
import {prepareNativeSkills} from './native-skills.js';
import {createHash} from 'node:crypto';

export const NATIVE_CODING_PROFILE='runtime-coding:native-project:v1';
export const isNativeCodingTask=contract=>contract?.metadataRefs?.includes(NATIVE_CODING_PROFILE)===true;

export function createNativeCodingRun(contract,gateway,options={}) {
  if(!isNativeCodingTask(contract)||contract.schemaVersion!=='runtime_os.coding.v2'
      ||contract.outputContract?.resultFormat!=='WORKSPACE_FILES'
      ||contract.authorization?.networkPolicy!=='NONE'
      ||!contract.authorization.allowedSideEffects?.includes('WORKSPACE_WRITE')
      ||!contract.authorization.allowedSideEffects?.includes('CODE_EXECUTION'))throw new TypeError('Native coding task policy required');
  for(const name of ['openCodeWorkspace','commitCodeWorkspace','executeCodeCommand','authorizeCodeTool','lookupCodeTool','finishCodeTool','checkpoint','finish'])
    if(typeof gateway[name]!=='function')throw new TypeError('Missing governed native coding port: '+name);
  const session=createNativeSession(contract,gateway,options),cancel=new AbortController();
  let agent,workspace,skills,preparing=null,lastExecution=null,running=false,initialAcceptanceCommand=null;
  const allowed=new Set(contract.authorization.allowedTools);
  const replayedTools=new Map();
  const admittedTools=new Set();
  async function prepare(){
    const initial=await gateway.openCodeWorkspace(contract,cancel.signal);
    initialAcceptanceCommand=initial.acceptanceCommand??null;
    if(cancel.signal.aborted)throw cancel.signal.reason;
    const routing=resolveToolRoutingConfig(initial.providerId??'runtime',initial.modelId??'', 'act',DEFAULT_MODEL_TOOL_ROUTING_RULES);
    if(initial.editStrategy && !['editor','patch'].includes(initial.editStrategy))throw new TypeError('Invalid host editing strategy');
    const strategy=initial.editStrategy??(routing.enableApplyPatch===true?'patch':'editor');
    lastExecution=initial.lastExecution??null;
    workspace=await createNativeCodeWorkspace({base:options.workspaceBase??tmpdir(),taskId:contract.taskId,scope:contract.scope,
      files:initial.files,restoredManifest:initial.manifest,executionMode:'commands',strategy,signal:cancel.signal,
      authorize:value=>gateway.authorizeCodeTool(contract,value,cancel.signal),
      commit:value=>gateway.commitCodeWorkspace(contract,value,cancel.signal),
      async runCommand(value){
        const receipt=await gateway.executeCodeCommand(contract,value,value.signal);
        lastExecution=receipt;
        return receipt;
      }});
    let tools=[...workspace.tools];
    if(allowed.has('skills')){
      if(!allowed.has('read_files'))throw new TypeError('Native Skills require the native read grant');
      skills=await prepareNativeSkills(contract,gateway,{sessionId:session.sessionId,signal:cancel.signal,observe:session.event});
      const merged=createDefaultTools({cwd:'/workspace/code',executors:{readFile:async(request,context)=>{
        const path=relative(skills.root,resolve(request.path));
        return path&&!path.startsWith('..')&&!isAbsolute(path)?skills.readFileExecutor(request,context):workspace.readFileExecutor(request,context);}},
        enableReadFiles:true,enableSearch:false,enableBash:false,enableWebFetch:false,enableEditor:false,enableApplyPatch:false,enableSkills:false,enableAskQuestion:false});
      tools=tools.filter(t=>t.name!=='read_files').concat(merged,skills.tools.filter(t=>t.name!=='read_files'));
    }
    for(const extension of initial.mcpServers??[]){
      if(typeof gateway.listCodeMcpTools!=='function'||typeof gateway.callCodeMcpTool!=='function')throw new TypeError('Governed MCP provider unavailable');
      tools.push(...await createMcpTools({serverName:extension.serverName,provider:{
        listTools:serverName=>gateway.listCodeMcpTools(contract,serverName,cancel.signal),
        callTool:request=>gateway.callCodeMcpTool(contract,request,request.context?.signal??cancel.signal),
      },retryable:false,maxRetries:0}));
    }
    if(new Set(tools.map(t=>t.name)).size!==tools.length||tools.some(t=>!allowed.has(t.name)))throw new TypeError('Native tools differ from immutable task grants');
    tools=tools.map(tool=>({...tool,async execute(input,context){
      // SDK prepares the whole batch (including beforeTool) before sequential execution.
      // A durable start therefore belongs here, immediately before actual tool I/O.
      const inputJson=JSON.stringify(input),signal=context.signal??cancel.signal;
      await gateway.authorizeCodeTool(contract,{action:'TOOL',tool:tool.name,operationId:context.toolCallId,input,inputJson,
        intentHash:createHash('sha256').update(inputJson).digest('hex')},signal);
      admittedTools.add(context.toolCallId);
      const cached=await gateway.lookupCodeTool(contract,{toolCallId:context.toolCallId},signal);
      if(cached?.output!==undefined)replayedTools.set(context.toolCallId,cached);
      const previous=replayedTools.get(context.toolCallId);
      return previous?previous.output:tool.execute(input,context);
    }}));
    const prepareTurn=sdkPrepareTurn(session.sessionId,()=>null,{
      compactionState:options.checkpoint?.compactionState,compactionCount:options.checkpoint?.compactionCount,
      statefulCompactionEnabled:true,
      protectLatestToolTail:true,
      technicalMemory:()=>({workspaceFiles:Object.keys(workspace.snapshot().files),
        ...(lastExecution?{lastExecution:{exitCode:lastExecution.exitCode,operationId:lastExecution.operationId}}:{})}),
      budgetSnapshot:typeof gateway.budget==='function'?context=>gateway.budget(contract,
        Math.ceil(Buffer.byteLength(JSON.stringify(context.messages))/4),context.signal):undefined,
    });
    agent=new Agent({agentId:session.sessionId,sessionId:session.sessionId,conversationId:session.sessionId,
      model:session.model,tools,prepareTurn,maxIterations:contract.budget.maxModelCalls,toolExecution:'sequential',
      systemPrompt:'You are Cline, an autonomous coding agent. Work in the authorized task project using the available tools and inspect actual execution results before finishing.',
      completionPolicy:{completionGuard:()=>{
        const current=workspace.snapshot();
        if(!Object.keys(current.files).length)return 'The task has no delivered project files yet.';
        if(initial.requireExecution!==false && (!lastExecution||lastExecution.resultAvailable===false||lastExecution.executorStopped!==true||lastExecution.exitCode!==0
            ||lastExecution.manifestHash!==current.manifest.sha256))return 'The current project version has no successful, stopped execution receipt. Inspect the actual tool feedback and continue within this task budget.';
        if(initial.acceptanceCommand&&lastExecution?.commandHash!==createHash('sha256').update(initial.acceptanceCommand).digest('hex'))
          return 'Run the task-declared verification command for the current project: '+initial.acceptanceCommand;
      }},
      hooks:{
        async beforeModel({snapshot,request}){
          if(workspace.requiresReload()){const current=await gateway.openCodeWorkspace(contract,cancel.signal);await workspace.refresh(current);lastExecution=current.lastExecution??lastExecution;}
          await session.checkpoint(snapshot.messages,{...prepareTurn.snapshot()});
          request.runtimeCheckpointOrdinal=session.state().checkpointOrdinal;},
        async beforeTool({toolCall}){
          if(!allowed.has(toolCall.toolName))throw new Error('Tool is not authorized');
        },
        async afterTool({toolCall,result}){
          const original=replayedTools.get(toolCall.toolCallId);if(original){result=original;replayedTools.delete(toolCall.toolCallId);}
          if(admittedTools.has(toolCall.toolCallId)){
            await gateway.finishCodeTool(contract,{toolCallId:toolCall.toolCallId,result},cancel.signal);
            admittedTools.delete(toolCall.toolCallId);
          }
          await session.event({type:'TOOL_FINISHED',tool:toolCall.toolName,toolCallId:toolCall.toolCallId,
            receiptReplayed:Boolean(original),
            status:result.isError||(Array.isArray(result.output)?result.output.some(v=>v?.success===false):result.output?.success===false)?'FAILED':'SUCCEEDED'});
          return{result};
        },
        async onEvent(event){if(event.type==='message-added'&&completeToolBoundary(event.snapshot.messages))
          await session.checkpoint(event.snapshot.messages,{...prepareTurn.snapshot()});},
      }});
    if(options.checkpoint?.messages){
      if(!completeToolBoundary(options.checkpoint.messages))throw new TypeError('Pending native tools require host reconciliation');
      agent.restore(options.checkpoint.messages);
      await session.event({type:'SESSION_TIMING',stage:'SDK_CHECKPOINT_RESTORED',sdkSessionId:session.sessionId,
        checkpointOrdinal:session.state().checkpointOrdinal,restoredMessageCount:options.checkpoint.messages.length});
    }
  }
  return {
    async run(){
      if(running)throw new Error('Native SDK session already running');running=true;
      let timer,result;
      try{
        const remaining=Date.parse(contract.budget.deadlineAt)-Date.now();
        if(remaining<=0)throw new Error('Runtime Coding deadline reached');
        timer=setTimeout(()=>{cancel.abort(Error('Runtime Coding deadline reached'));agent?.abort(cancel.signal.reason);},remaining);
        preparing??=prepare();await preparing;
        const pending=options.checkpoint?agent.continue():agent.run(contract.objective.instruction+'\nTask project files: '+JSON.stringify(Object.keys(workspace.snapshot().files))+'\nProject paths are relative or under /workspace/code.'+(initialAcceptanceCommand?'\nTask verification command: '+initialAcceptanceCommand:''));
        result=await pending;
        const outcome=await gateway.finish(contract,{status:result.status,summary:result.outputText??'',
          workspaceManifest:workspace.snapshot().manifest,lastExecutionOperationId:lastExecution?.operationId,
          iterations:result.iterations});
        const authoritative=({COMPLETED:'completed',FAILED:'failed',CANCELLED:'aborted',BUDGET_EXCEEDED:'failed'})[outcome?.status];
        if(outcome?.status&&!authoritative)throw Error('Runtime task did not reach a terminal outcome');
        const status=authoritative??result.status;
        return{taskId:contract.taskId,status,text:result.outputText,usage:outcome?.usage??result.usage,
          ...(result.error?{error:{name:result.error.name??'Error',message:String(result.error.message??result.error).slice(0,512)}}:{}),
          workspaceManifest:workspace.snapshot().manifest,sdkSessionId:session.sessionId,...session.state()};
      }catch(error){
        try{await gateway.finish(contract,{status:cancel.signal.aborted?'aborted':'failed',summary:String(error?.message??error).slice(0,512)});}catch{}
        throw error;
      }finally{clearTimeout(timer);running=false;await skills?.dispose();await workspace?.dispose();}
    },
    abort(reason='Runtime cancellation'){cancel.abort(Error(reason));agent?.abort(cancel.signal.reason);
      void Promise.resolve(gateway.cancelCodeCommands?.(contract)).catch(()=>{});},
    snapshot(){return agent?.snapshot()??{messages:options.checkpoint?.messages??[],status:'idle'};},
  };
}
