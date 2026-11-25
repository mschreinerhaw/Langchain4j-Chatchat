/** Resolve a task's signed model only through the deployment's fixed Java authority.
 * Credentials stay in the root-owned broker record, never in a Worker grant.
 */
export async function resolveRuntimeModel(contract,authority,grant,{fetchImpl=fetch}={}){
 const url=new URL(authority);
 if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.search||url.hash)throw Error('Invalid fixed model authority');
 const response=await fetchImpl(url,{method:'POST',headers:{Authorization:'Bearer '+grant},redirect:'manual',signal:AbortSignal.timeout(Math.max(1,Math.min(30000,contract.expiresAt-Date.now())))});
 if(response.status!==200)throw Object.assign(Error('Runtime model authority rejected task: HTTP '+response.status),{status:response.status});
 const chunks=[];let size=0;
 for await(const chunk of response.body){size+=chunk.length;if(size>32768)throw Error('Model connection byte budget exceeded');chunks.push(Buffer.from(chunk));}
 let value;try{value=JSON.parse(Buffer.concat(chunks).toString());}catch{throw Error('Invalid model authority response');}
 if(!value||Object.keys(value).some(k=>!['apiKey','baseUrl','model','maxTokens','contextWindow','requestedReasoningEffort','reasoningEffort','reasoningParameter'].includes(k))||!['model','baseUrl','apiKey'].every(k=>typeof value[k]==='string'&&value[k].length>0)||value.model!==contract.runtimeModel.providerModel)throw Error('Runtime model binding differs');
 if(value.maxTokens!==undefined&&(!Number.isSafeInteger(value.maxTokens)||value.maxTokens!==-1&&value.maxTokens<1))throw Error('Invalid published model token limit');
 if(value.contextWindow!==undefined&&(!Number.isSafeInteger(value.contextWindow)||value.contextWindow<=Math.max(0,value.maxTokens??0)))throw Error('Invalid published model context window');
 const requested=value.requestedReasoningEffort,effective=value.reasoningEffort;
 const format=value.reasoningParameter;
 if(requested!==undefined&&!['auto','off','minimal','low','medium','high','xhigh'].includes(requested)
  ||format!==undefined&&(effective===undefined||!validEffort(format,effective))
  ||effective!==undefined&&(!requested||!validEffort(format??'standard',effective))
  ||requested==='off'&&effective!==undefined
  ||effective!==undefined&&requested!=='auto'&&format===undefined&&requested!==effective)
   throw Error('Invalid frozen model reasoning');
 const upstream=new URL(value.baseUrl);
 if(!['http:','https:'].includes(upstream.protocol)||upstream.username||upstream.password||upstream.search||upstream.hash)throw Error('Invalid published model endpoint');
 return value;
}

/** Apply the authorized publication after Core builds its request. -1 omits a provider cap. */
export function applyPublishedModelLimit(body,profile,responses=false){
 if(profile.maxTokens===undefined)return body;
 if(!Number.isSafeInteger(profile.maxTokens)||profile.maxTokens!==-1&&profile.maxTokens<1)throw Error('Invalid published model token limit');
 delete body.max_tokens;delete body.max_completion_tokens;delete body.max_output_tokens;
 if(profile.maxTokens>0)body[responses?'max_output_tokens':'max_tokens']=profile.maxTokens;
 return body;
}

function validEffort(format,effort){
 if(format==='hunyuan-template')return ['no_think','low','high'].includes(effort);
 if(format==='deepseek-thinking')return ['low','high','max'].includes(effort);
 return (format==='standard'||format==='qwen-thinking'&&effort!=='no_think')&&['none','minimal','low','medium','high','xhigh','max','no_think'].includes(effort);
}

/** A task with a frozen preference owns the provider parameter, not the Worker. */
export function applyPublishedReasoning(body,profile,responses=false){
 if(profile.requestedReasoningEffort===undefined)return body;
 delete body.reasoning_effort;
 delete body.reasoning;
 const effort=profile.reasoningEffort,format=profile.reasoningParameter;
 if(body.chat_template_kwargs&&typeof body.chat_template_kwargs==='object')
  delete body.chat_template_kwargs.reasoning_effort;
 delete body.thinking;
 delete body.enable_thinking;
 delete body.thinking_budget;
 if(effort===undefined)return body;
 if(!validEffort(format??'standard',effort))throw Error('Invalid provider reasoning configuration');
 if(format==='hunyuan-template'){
  if(responses)throw Error('Hunyuan reasoning requires Chat Completions');
  body.chat_template_kwargs={...body.chat_template_kwargs,reasoning_effort:effort};
 }else if(responses){
  body.reasoning={effort};
 }else{
  body.reasoning_effort=effort;
  if(format==='deepseek-thinking')body.thinking={type:'enabled'};
  if(format==='qwen-thinking'){
   body.enable_thinking=effort!=='none';
  }
 }
 return body;
}
