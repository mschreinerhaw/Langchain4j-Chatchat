import MarkdownIt from 'markdown-it';
import { apiRequest, getStoredAuthSession } from '../../services/api';
import { buildPythonLineDiff } from '../utils/pythonCodeDiff';
import PythonCodeDiff from '../../components/PythonCodeDiff.vue';
import ReportMarkdown from '../../components/ReportMarkdown.vue';

const base='/data-science/python/code-reuse';
const terminal=new Set(['COMPLETED','FAILED','CANCELLED','BUDGET_EXCEEDED','PARTIAL']);
const markdown=new MarkdownIt({html:false,linkify:false});
export async function sourceHash(content) { return [...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(content)))].map(b=>b.toString(16).padStart(2,'0')).join(''); }
export function projectScriptPath(script,folders) {
  const result=[script.fileName],seen=new Set();let parent=script.folderId;
  while(parent){if(seen.has(parent)||seen.size>=16)throw new Error('工程目录关系无效');seen.add(parent);const folder=folders.find(f=>f.id===parent);if(!folder)throw new Error('工程目录不可用');result.unshift(folder.name);parent=folder.parentId;}
  return result.join('/');
}
export default {
  name:'PythonReusePanel',components:{PythonCodeDiff,ReportMarkdown},
  props:{scripts:{type:Array,default:()=>[]},folders:{type:Array,default:()=>[]},skillIds:{type:Array,default:()=>[]},currentScriptId:String,assetId:String,modelName:String,workspaceContext:Object},emits:['applied'],
  data:()=>({selected:[],uploaded:[],dataFiles:[],dataIds:[],snippet:'',snippetPath:'helpers.py',entryPath:'',instruction:'',state:null,requestId:'',snapshotSha256:'',consent:false,preparing:false,executing:false,error:'',notice:'',operation:'',followLatest:true,applyPaths:[],timer:null,disposed:false,visible:false}),
  computed:{
    candidateScripts(){return this.scripts.filter(s=>!this.assetId||s.assetId===this.assetId);},
    running(){return this.executing||['PREPARING','CREATED'].includes(this.state?.status)||!!this.state?.outcome&&!terminal.has(this.state.status);},
    canExecute(){return this.consent&&!this.executing&&(!this.running||this.state?.recoveryRequired===true);},
    executions(){return this.state?.executions||[];},versions(){return this.state?.codeVersions||[];},
    progressEvents(){return (this.state?.events||[]).filter(e=>['TASK_CREATED','TASK_STARTED','CODE_COMMITTED','EXECUTION_STARTED','EXECUTION_FINISHED','VALIDATION_RECORDED','CANCELLATION_REQUESTED','TASK_FINISHED'].includes(e.type)||['CODE_READY','MODEL_STARTED'].includes(e.payload?.type)||e.payload?.tool==='python_execute'&&['TOOL_STARTED','TOOL_FINISHED'].includes(e.payload?.type));},
    diffs(){const version=this.versions.find(v=>v.operationId===this.operation)||this.versions.at(-1);return (version?.project?.files||[]).map(file=>{const source=this.state?.sources?.find(s=>s.path===file.path);return {...file,importable:source?.kind!=='SCRIPT',diff:buildPythonLineDiff(source?.content||'',file.content)};});},
    storageKey(){const auth=getStoredAuthSession();return `python-code-reuse:${auth?.user?.tenantId||''}:${auth?.user?.id||''}:${this.workspaceContext?.workspaceId||'data-science'}`;}
  },
  watch:{currentScriptId(id){if(!this.state&&id)this.selected=[id];},operation(){this.applyPaths=[];}},
  beforeUnmount(){this.disposed=true;clearTimeout(this.timer);},
  methods:{
    renderMarkdown(value){return markdown.render(value);},scriptPath(script){return projectScriptPath(script,this.folders);},
    progressLabel(event){const kind=event.payload?.type||event.type;return ({TASK_CREATED:'分析任务已受理',TASK_STARTED:'正在分析已有代码',MODEL_STARTED:'Cline 正在分析代码或核对结果',CODE_READY:'代码版本已准备',CODE_COMMITTED:'代码已归档',EXECUTION_STARTED:'Python 开始执行',TOOL_STARTED:'Python 执行已请求',TOOL_FINISHED:'Python 执行回执已返回',EXECUTION_FINISHED:'Python 已停止',VALIDATION_RECORDED:'产物校验已记录',CANCELLATION_REQUESTED:'已请求取消',TASK_FINISHED:'任务已结束'})[kind]||event.summary;},
    options(method='GET',body){return {workspaceContext:this.workspaceContext,method,...(body?{body:JSON.stringify(body)}:{})};},
    async opened(event){this.visible=event.target.open;if(!this.visible){clearTimeout(this.timer);return;}try{this.dataFiles=await apiRequest('/data-science/user-assets?limit=200',this.options());this.dataFiles=this.dataFiles.filter(f=>!f.fileName.endsWith('.py')&&['DOCUMENT','DATASET','ARTIFACT'].includes(f.kind));if(!this.selected.length&&this.currentScriptId)this.selected=[this.currentScriptId];if(!this.entryPath&&this.selected.length)this.entryPath=this.scriptPath(this.scripts.find(s=>s.id===this.selected[0]));if(!this.requestId)this.requestId=localStorage.getItem(this.storageKey)||'';if(this.requestId)await this.refresh();}catch(e){this.error=e.message;}},
    async upload(event){this.error='';try{for(const file of event.target.files){if(!file.name.endsWith('.py'))continue;const body=new FormData();body.append('file',file);const saved=await apiRequest('/data-science/user-assets',{workspaceContext:this.workspaceContext,method:'POST',body});this.uploaded.push({...saved,path:file.webkitRelativePath||file.name});}if(!this.entryPath&&this.uploaded.length)this.entryPath=this.uploaded[0].path;}catch(e){this.error=e.message;}finally{event.target.value='';}},
    async prepare(){this.preparing=true;this.error='';this.notice='';try{
      const references=[];for(const id of this.selected){const script=this.scripts.find(s=>s.id===id);references.push({kind:'SCRIPT',id,version:script.currentVersion,path:this.scriptPath(script),sha256:await sourceHash(script.sourceCode)});}
      for(const file of this.uploaded)references.push({kind:'ASSET',id:file.id,version:1,path:file.path,sha256:file.sha256});
      if(this.snippet.trim())references.push({kind:'SNIPPET',path:this.snippetPath,content:this.snippet,sha256:await sourceHash(this.snippet)});
      const requestId=`reuse-${crypto.randomUUID()}`;
      const prepared=await apiRequest(base,this.options('POST',{requestId,instruction:this.instruction,entryPath:this.entryPath,references,dataFileIds:this.dataIds,modelName:this.modelName,skillIds:this.skillIds}));
      this.requestId=requestId;this.snapshotSha256=prepared.snapshotSha256;this.state={...prepared,status:'PREPARED'};this.consent=false;this.operation='';this.followLatest=true;this.applyPaths=[];localStorage.setItem(this.storageKey,requestId);
    }catch(e){this.error=e.message;}finally{this.preparing=false;}},
    schedule(){clearTimeout(this.timer);if(!this.disposed&&this.visible&&this.state&&!terminal.has(this.state.status))this.timer=setTimeout(()=>this.refresh(),this.running?1000:3000);},
    async refresh(){if(!this.requestId||this.disposed)return;try{const previous=this.state;this.state=await apiRequest(`${base}/${encodeURIComponent(this.requestId)}`,this.options());this.snapshotSha256=this.state.snapshotSha256||this.snapshotSha256;if((this.followLatest||!this.operation)&&this.versions.length)this.operation=this.versions.at(-1).operationId;this.notice='';if(previous?.outcome&&!this.state.outcome)this.notice='执行受理状态正在同步';}catch(e){this.notice=`状态连接暂不可用，保留任务并继续核对：${e.message}`;}finally{this.schedule();}},
    async execute(){if(!this.canExecute)return;this.error='';this.executing=true;this.schedule();try{await apiRequest(`${base}/${this.requestId}/execute`,this.options('POST',{snapshotSha256:this.snapshotSha256,authorizeExecution:true}));}catch(e){this.error=`执行请求返回：${e.message}。任务是否结束以服务端状态为准。`;}finally{this.executing=false;await this.refresh();}},
    async cancel(){try{this.state=await apiRequest(`${base}/${this.requestId}/cancel`,this.options('POST',{}));this.notice='已请求取消，等待后端停止回执';this.schedule();}catch(e){this.error=e.message;}},
    async apply(){const version=this.versions.find(v=>v.operationId===this.operation)||this.versions.at(-1);try{await apiRequest(`${base}/${this.requestId}/apply`,this.options('POST',{operationId:version.operationId,codeSha256:version.sha256,paths:this.applyPaths,targetAssetId:this.assetId}));this.applyPaths=[];await this.refresh();this.notice='代码已回写并保存新版本，原版本保留';this.$emit('applied');}catch(e){this.error=e.message;}}
  }
};
