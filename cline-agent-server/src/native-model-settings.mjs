// Optional request parameters belong to this isolated Native session, never the
// shared provider store. An absent option preserves the installed Core defaults.
export function nativeModelSettings(config) {
 const options=config.modelOptions;
 if(options===undefined)return {global:{},providers:null,models:null};
 if(!options||typeof options!=='object'||Array.isArray(options))throw Error('Invalid modelOptions');
 for(const key of Object.keys(options))if(!['maxOutputTokens','contextWindow','thinking','reasoningEffort'].includes(key))throw Error('Unknown modelOptions field: '+key);
 const global={},modelInfo={};
 if(options.maxOutputTokens!==undefined){
  if(!Number.isSafeInteger(options.maxOutputTokens)||options.maxOutputTokens<=0)throw Error('Invalid model maxOutputTokens');
  modelInfo.maxTokens=options.maxOutputTokens;
 }
 if(options.contextWindow!==undefined){
  if(!Number.isSafeInteger(options.contextWindow)||options.contextWindow<=Math.max(0,modelInfo.maxTokens??0))throw Error('Invalid model context window');
  modelInfo.contextWindow=options.contextWindow;
 }
 if(Object.keys(modelInfo).length){
  global.planModeOpenAiModelInfo=modelInfo;
  global.actModeOpenAiModelInfo=modelInfo;
 }
 if(options.thinking!==undefined&&typeof options.thinking!=='boolean')throw Error('Invalid model thinking');
 if(options.reasoningEffort!==undefined&&!['none','minimal','low','medium','high','xhigh'].includes(options.reasoningEffort))throw Error('Invalid model reasoningEffort');
 if(options.thinking===false&&options.reasoningEffort&&options.reasoningEffort!=='none')throw Error('Conflicting model reasoning settings');
 const reasoning={...(options.thinking!==undefined?{enabled:options.thinking}:{}),...(options.reasoningEffort?{effort:options.reasoningEffort}:{})};
 return {global,models:options.contextWindow!==undefined?{version:1,providers:{'openai-compatible':{models:{[config.model]:modelInfo}}}}:null,
  providers:Object.keys(reasoning).length?{version:1,modes:{},providers:{'openai-compatible':{settings:{provider:'openai-compatible',model:config.model,reasoning},updatedAt:new Date().toISOString(),tokenSource:'manual'}}}:null};
}
