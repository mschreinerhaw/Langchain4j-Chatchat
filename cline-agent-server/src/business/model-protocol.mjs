// Wire projection only: inference and its durable admission stay in Runtime.
export const LOCAL_TOOLS = new Set(['read_files','search_codebase','skills','editor','apply_patch','submit_and_exit']);
export function permittedTool(name,contract) {
 if(LOCAL_TOOLS.has(name))return !['editor','apply_patch'].includes(name)||contract.authorization.allowedSideEffects.includes('CODE_EXECUTION');
 return name.startsWith('runtime__')&&contract.authorization.allowedTools.includes(name.slice(9));
}
const textParts=value=>typeof value==='string'?[{type:'text',text:value}]:value==null?[]:value.map(p=>{
 if(p.type!=='text')throw Error('Unsupported native model content: '+p.type);
 return {type:'text',text:p.text};
});
export function projectRequest(body,contract,ordinal) {
 const names=new Map(),messages=[],system=[];
 for(const m of body.messages??[]) {
  if(['system','developer'].includes(m.role)){system.push(...textParts(m.content).map(p=>p.text));continue;}
  const content=textParts(m.content);
  if(m.role==='assistant')for(const c of m.tool_calls??[]) {
   if(!permittedTool(c.function.name,contract))throw Error('Unauthorized native tool history');
   names.set(c.id,c.function.name);
   content.push({type:'tool-call',toolCallId:c.id,toolName:c.function.name,input:JSON.parse(c.function.arguments)});
  }
  if(m.role==='tool') {
   const name=names.get(m.tool_call_id);if(!name)throw Error('Unbound native tool result');
   messages.push({role:'tool',content:[{type:'tool-result',toolCallId:m.tool_call_id,toolName:name,output:content.map(p=>p.text).join('\n')}]});
  }else {if(!['user','assistant'].includes(m.role))throw Error('Unsupported native model role');messages.push({role:m.role,content});}
 }
 const tools=(body.tools??[]).filter(t=>permittedTool(t.function.name,contract)).map(t=>({name:t.function.name,description:t.function.description??'',inputSchema:t.function.parameters}));
 return {operationId:`model-${ordinal}`,managedAttempt:true,modelResponseFeedbackVersion:'runtime_os.coding.model_feedback.v1',systemPrompt:system.join('\n\n'),messages,tools};
}
export function projectResponse(answer,model) {
 const feedback=answer.modelResponseFeedback;
 const recoverable=answer.error&&feedback?.schemaVersion==='runtime_os.coding.model_feedback.v1'&&feedback.repairAllowed===true
  &&['MODEL_OUTPUT_TRUNCATED','MODEL_TOOL_ARGUMENTS_INVALID'].includes(feedback.diagnosticCode)
  &&Number.isInteger(feedback.maxRepairAttempts)&&feedback.maxRepairAttempts>=1&&feedback.maxRepairAttempts<=8;
 if(answer.error&&(!recoverable||feedback.diagnosticCode==='MODEL_TOOL_ARGUMENTS_INVALID'&&!answer.nativeToolCalls?.length))
  throw Object.assign(Error('Runtime rejected model response'),{feedback});
 // Preserve actual provider fragments so the official Core can perform its own
 // bounded recovery. Never complete malformed JSON or synthesize a tool result.
 const calls=recoverable?answer.nativeToolCalls??[]:answer.toolCalls??[];
 const tool_calls=calls.map(c=>({id:c.id,type:'function',function:{name:c.name,arguments:recoverable?c.arguments:JSON.stringify(c.input)}}));
 const finish_reason=recoverable?(feedback.diagnosticCode==='MODEL_OUTPUT_TRUNCATED'?'length':'tool_calls')
  :{STOP:'stop',TOOL_EXECUTION:'tool_calls',LENGTH:'length',CONTENT_FILTER:'content_filter'}[answer.finishReason]??(tool_calls.length?'tool_calls':'stop');
 return {id:answer.requestId??'runtime-model-response',object:'chat.completion',created:Math.floor(Date.now()/1000),model,
  choices:[{index:0,message:{role:'assistant',content:answer.text??null,...(tool_calls.length?{tool_calls}:{})},finish_reason}],
  usage:{prompt_tokens:answer.usage?.inputTokens??0,completion_tokens:answer.usage?.outputTokens??0,total_tokens:(answer.usage?.inputTokens??0)+(answer.usage?.outputTokens??0)}};
}
export function writeCompletion(res,completion,stream) {
 if(!stream){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(completion));return;}
 res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-store'});
 const choice=completion.choices[0],base={...completion,object:'chat.completion.chunk'};delete base.usage;
 const delta={...choice.message};if(delta.tool_calls)delta.tool_calls=delta.tool_calls.map((c,index)=>({...c,index}));
 res.write('data: '+JSON.stringify({...base,choices:[{index:0,delta,finish_reason:null}]})+'\n\n');
 res.write('data: '+JSON.stringify({...base,usage:completion.usage,choices:[{index:0,delta:{},finish_reason:choice.finish_reason}]})+'\n\n');
 res.end('data: [DONE]\n\n');
}
