/** Resolve a task's signed model only through the deployment's fixed Java authority.
 * Credentials stay in the root-owned broker record, never in a Worker grant.
 */
export async function resolveRuntimeModel(contract,authority,grant,{fetchImpl=fetch}={}){
 const url=new URL(authority);
 if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.search||url.hash)throw Error('Invalid fixed model authority');
 const response=await fetchImpl(url,{method:'POST',headers:{Authorization:'Bearer '+grant},redirect:'manual',signal:AbortSignal.timeout(Math.max(1,Math.min(30000,contract.expiresAt-Date.now())))});
 if(response.status!==200)throw Error('Runtime model authority rejected task: HTTP '+response.status);
 const chunks=[];let size=0;
 for await(const chunk of response.body){size+=chunk.length;if(size>32768)throw Error('Model connection byte budget exceeded');chunks.push(Buffer.from(chunk));}
 let value;try{value=JSON.parse(Buffer.concat(chunks).toString());}catch{throw Error('Invalid model authority response');}
 if(!value||Object.keys(value).some(k=>!['apiKey','baseUrl','model','maxTokens'].includes(k))||!['model','baseUrl','apiKey'].every(k=>typeof value[k]==='string'&&value[k].length>0)||value.model!==contract.runtimeModel.providerModel)throw Error('Runtime model binding differs');
 if(value.maxTokens!==undefined&&(!Number.isSafeInteger(value.maxTokens)||value.maxTokens!==-1&&value.maxTokens<1))throw Error('Invalid published model token limit');
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
