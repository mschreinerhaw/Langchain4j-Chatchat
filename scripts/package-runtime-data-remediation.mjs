// Package only reviewed class families, Runtime adapters and the built UI.
// Optional third argument reuses an already verified UI build for a Java-only change.
import {mkdir,readdir,readFile,writeFile,copyFile} from 'node:fs/promises';
import {resolve,join,dirname} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const root=resolve(import.meta.dirname,'..'),out=resolve(process.argv[2]??join(root,'target/runtime-data-remediation/bundle'));
const families={
 api:['com/chatchat/api/runtime/evidence/JdbcRuntimeEvidenceStore','com/chatchat/api/assets/SiloAssetBlobStore','com/chatchat/api/exploration/ExplorationAgentBridge','com/chatchat/api/exploration/ExplorationWorkspaceController','com/chatchat/api/assets/AssetAnalysisController','com/chatchat/api/runtime/coding/analysis/CodingAnalysisService','com/chatchat/api/runtime/coding/cline/ClineToolProfiles',
  'com/chatchat/api/runtime/coding/data/CodingDataSupplyService','com/chatchat/api/runtime/coding/data/CodingInputFilePublisher',
  'com/chatchat/api/runtime/coding/data/CodingTableCodec','com/chatchat/api/runtime/coding/cline/CodingInputLayout','com/chatchat/api/runtime/coding/knowledge/CodingKnowledgeService',
  'com/chatchat/api/runtime/coding/knowledge/CodingResourceReader','com/chatchat/api/runtime/coding/knowledge/CodingInternetAccess','com/chatchat/api/runtime/coding/knowledge/CodingDataObservation',
  'com/chatchat/api/runtime/coding/execution/CodingExecutionFeedbackService','com/chatchat/api/runtime/coding/cline/ClinePythonCallbackController',
  'com/chatchat/api/runtime/coding/cline/ClineModelCallbackController','com/chatchat/api/model/InternetSearchProbeController'],
 mcp:['com/chatchat/mcpserver/market/ResourceExplorationReadController'],
 agents:['com/chatchat/agents/orchestration/analysis/graph/AnalysisCodingPort','com/chatchat/agents/orchestration/analysis/governance/AnalysisCoverageCoordinator','com/chatchat/agents/orchestration/analysis/dataset/DataSupplyStatus'],
 common:['com/chatchat/common/runtime/coding/CodingRuntimeContext','com/chatchat/common/runtime/coding/CodingDataStatus'],
 market:['com/chatchat/runtime/market/storage/FinancialDataStore','com/chatchat/runtime/market/config/MarketModuleProperties'],
 news:['com/chatchat/runtime/news/internet/InternetSearchService']};
const modules={api:'chatchat-api',mcp:'chatchat-mcp-server',agents:'chatchat-agents',common:'chatchat-common',market:'chatchat-runtime-market',news:'chatchat-runtime-news'};
const files={};
async function add(src,name){const bytes=await readFile(src);await mkdir(dirname(join(out,name)),{recursive:true});await copyFile(src,join(out,name));files[name]=createHash('sha256').update(bytes).digest('hex');}
for(const [module,names] of Object.entries(families))for(const name of names){
 const directory=join(root,modules[module],'target/classes',dirname(name)),base=name.split('/').at(-1);
 const matches=(await readdir(directory)).filter(n=>n===base+'.class'||n.startsWith(base+'$')&&n.endsWith('.class'));
 if(!matches.includes(base+'.class'))throw Error('Missing compiled family '+name);
 for(const match of matches)await add(join(directory,match),module+'/'+dirname(name)+'/'+match);
}
for(const name of ['knowledge-tools.js','code-repair.js','sdk-adapter.js','runner.js'])await add(join(root,'coding-agent-node/dist',name),'node/'+name);
async function ui(dir,relative=''){for(const entry of await readdir(dir,{withFileTypes:true})){
 const name=relative?relative+'/'+entry.name:entry.name;
 if(entry.isDirectory())await ui(join(dir,entry.name),name);else await add(join(dir,entry.name),'static/'+name);
}}
await ui(process.argv[3]?resolve(process.argv[3]):join(root,'chatchat-api/src/main/resources/static'));
await writeFile(join(out,'bundle-manifest.json'),JSON.stringify({sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),files},null,2));
console.log(JSON.stringify({out,fileCount:Object.keys(files).length}));
