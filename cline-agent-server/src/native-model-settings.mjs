// Optional request parameters belong to this isolated Native session, never the
// shared provider store. An absent option preserves the installed Core defaults.
export function nativeModelSettings(config) {
 const options=config.modelOptions;
 if(options===undefined)return {global:{},providers:null};
 if(!options||typeof options!=='object'||Array.isArray(options))throw Error('Invalid modelOptions');
 for(const key of Object.keys(options))if(!['maxOutputTokens','thinking','reasoningEffort'].includes(key))throw Error('Unknown modelOptions field: '+key);
 const global={};
 if(options.maxOutputTokens!==undefined){
  if(!Number.isSafeInteger(options.maxOutputTokens)||options.maxOutputTokens<=0)throw Error('Invalid model maxOutputTokens');
  global.planModeOpenAiModelInfo={maxTokens:options.maxOutputTokens};
  global.actModeOpenAiModelInfo={maxTokens:options.maxOutputTokens};
 }
 if(options.thinking!==undefined&&typeof options.thinking!=='boolean')throw Error('Invalid model thinking');
 if(options.reasoningEffort!==undefined&&!['none','minimal','low','medium','high','xhigh'].includes(options.reasoningEffort))throw Error('Invalid model reasoningEffort');
 if(options.thinking===false&&options.reasoningEffort&&options.reasoningEffort!=='none')throw Error('Conflicting model reasoning settings');
 const reasoning={...(options.thinking!==undefined?{enabled:options.thinking}:{}),...(options.reasoningEffort?{effort:options.reasoningEffort}:{})};
 return {global,providers:Object.keys(reasoning).length?{version:1,modes:{},providers:{'openai-compatible':{settings:{provider:'openai-compatible',model:config.model,reasoning},updatedAt:new Date().toISOString(),tokenSource:'manual'}}}:null};
}
