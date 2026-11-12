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
 if(!value||Object.keys(value).sort().join(',')!=='apiKey,baseUrl,model'||!['model','baseUrl','apiKey'].every(k=>typeof value[k]==='string'&&value[k].length>0)||value.model!==contract.runtimeModel.providerModel)throw Error('Runtime model binding differs');
 const upstream=new URL(value.baseUrl);
 if(!['http:','https:'].includes(upstream.protocol)||upstream.username||upstream.password||upstream.search||upstream.hash)throw Error('Invalid published model endpoint');
 return value;
}
