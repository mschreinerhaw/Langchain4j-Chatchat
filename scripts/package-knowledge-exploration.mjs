import { mkdirSync, readdirSync, readFileSync, writeFileSync, copyFileSync, statSync } from 'node:fs';
import { resolve, dirname, relative, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const root=resolve(import.meta.dirname,'..');
const output=resolve(root,'target',`knowledge-release-${Date.now()}`);mkdirSync(output,{recursive:true});
const groups={
  'chatchat-common':['com/chatchat/common/runtime/coding/CodingRuntimeContext'],
  'chatchat-knowledge-base':['com/chatchat/knowledgebase/search/service/SearchService','com/chatchat/knowledgebase/search/config/SearchProperties',
    'com/chatchat/knowledgebase/search/index/infrastructure/opensearch/OpenSearchDocumentIndexService'],
  'chatchat-chat':['com/chatchat/chat/interaction/service/CodingAnswerPort','com/chatchat/chat/interaction/service/handler/RoleChatModeHandler'],
  'api':['com/chatchat/api/assets/UserAssetService','com/chatchat/api/runtime/coding/analysis/ClineContextAnswerService',
    'com/chatchat/api/runtime/coding/analysis/CodingAnalysisService','com/chatchat/api/runtime/coding/cline/ClineCodingAdapter',
    'com/chatchat/api/runtime/coding/cline/ClineModelCallbackController','com/chatchat/api/runtime/coding/cline/ClineToolProfiles',
    'com/chatchat/api/runtime/coding/evidence/CodingEvidenceCollector','com/chatchat/api/security/ApiPermissionAuthorizationFilter',
    'com/chatchat/api/agent/task/AgentTaskEventStreamService','com/chatchat/api/runtime/trace/AgentRuntimeEventStreamService',
    'com/chatchat/api/runtime/coding/knowledge/CodingKnowledgeService','com/chatchat/api/runtime/coding/knowledge/KnowledgeDisclosureGuard',
    'com/chatchat/api/runtime/coding/knowledge/KnowledgeResponseAdvice','com/chatchat/api/runtime/coding/knowledge/KnowledgeCitationController'],
};
const resources=process.argv.includes('--resources');
if(resources) {
  groups['chatchat-common'].push('com/chatchat/common/retrieval/AgentResourcePolicy','com/chatchat/common/retrieval/AgentResourceScopePort','com/chatchat/common/retrieval/ResourceAuthorizationPort');
  groups['chatchat-chat'].push('com/chatchat/chat/skills/runtime/AgentResourceScopeService','com/chatchat/chat/interaction/service/handler/AgentChatModeHandler','com/chatchat/chat/skills/catalog/SkillCatalogService');
  groups.api.push('com/chatchat/api/runtime/coding/knowledge/CodingResourceReader','com/chatchat/api/controller/agent/AgentWorkshopController',
    'com/chatchat/api/enterprise/controller/ResourceGrantAdminController','com/chatchat/api/runtime/coding/workspace/CodingWorkspaceProofVerifier');
  groups['chatchat-runtime-market']=['com/chatchat/runtime/market/storage/FinancialAssetCatalogService','com/chatchat/runtime/market/storage/MarketAssetCatalogIndex','com/chatchat/runtime/market/storage/FinancialDataStore'];
  groups['chatchat-mcp-server']=['com/chatchat/mcpserver/market/ResourceExplorationReadController','com/chatchat/mcpserver/market/McpMarketAssetCatalogIndex',
    'com/chatchat/mcpserver/search/engine/OpenSearchMcpSearchService','com/chatchat/mcpserver/health/McpReadinessController',
    'com/chatchat/mcpserver/news/runtime/NewsRuntimeClient'];
  groups['chatchat-runtime-news']=['com/chatchat/runtime/news/model/NewsSearchQuery','com/chatchat/runtime/news/store/opensearch/OpenSearchNewsDocumentStore',
    'com/chatchat/runtime/news/store/opensearch/NewsBudgetedOpenSearchRequests','com/chatchat/runtime/news/api/tool/NewsResourceReadController'];
}
const copy=(from,to)=>{mkdirSync(dirname(to),{recursive:true});copyFileSync(from,to);};
function tree(from,to){for(const name of readdirSync(from)){const src=join(from,name),dest=join(to,name);if(statSync(src).isDirectory())tree(src,dest);else copy(src,dest);}}
for(const [module,prefixes] of Object.entries(groups))for(const prefix of prefixes){
  const classes=resolve(root,module==='api'?'chatchat-api':module,'target/classes');
  const dir=dirname(resolve(classes,prefix)),base=prefix.split('/').at(-1);
  if(!statSync(resolve(classes,prefix+'.class')).isFile())throw Error('Compile before packaging: '+prefix);
  for(const name of readdirSync(dir))if(name===base+'.class'||name.startsWith(base+'$'))copy(join(dir,name),resolve(output,'patch',module,dirname(prefix),name));
}
copy(resolve(root,'coding-agent-node/skills/catalog.json'),resolve(output,'patch/chatchat-chat/coding-skills/catalog.json'));
if(resources)copy(resolve(root,'chatchat-mcp-server/src/main/resources/mcp-admin-menus.json'),resolve(output,'patch/chatchat-mcp-server/mcp-admin-menus.json'));
const sealedStatic=process.env.KNOWLEDGE_SEALED_STATIC_DIR;
tree(sealedStatic?resolve(sealedStatic):resolve(root,'chatchat-api/src/main/resources/static'),resolve(output,'static'));
for(const name of ['runner.js','server.js','skills.js','knowledge-tools.js'])copy(resolve(root,'coding-agent-node/dist',name),resolve(output,'worker/dist',name));
copy(resolve(root,'coding-agent-node/skills/catalog.json'),resolve(output,'worker/skills/catalog.json'));
tree(resolve(root,'coding-agent-node/skills/opensearch-knowledge-exploration'),resolve(output,'worker/skills/opensearch-knowledge-exploration'));
if(resources)for(const name of ['financial-news-analysis','financial-market-analysis','evidence-based-answering'])
  tree(resolve(root,'coding-agent-node/skills',name),resolve(output,'worker/skills',name));
// Test the same worker sources against its pinned, installed SDK on the deployment host.
tree(resolve(root,'coding-agent-node/src'),resolve(output,'verification/src'));
tree(resolve(root,'coding-agent-node/dist'),resolve(output,'verification/dist'));
tree(resolve(root,'coding-agent-node/test'),resolve(output,'verification/test'));
tree(resolve(root,'coding-agent-node/fixtures'),resolve(output,'verification/fixtures'));
tree(resolve(root,'coding-agent-node/scripts'),resolve(output,'verification/scripts'));
copy(resolve(root,'chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py'),
  resolve(output,'verification-common/src/main/resources/runtime/coding/python-project-adapter.py'));
copy(resolve(root,'coding-agent-node/package.json'),resolve(output,'verification/package.json'));
copy(resolve(root,'deploy/docker/application/node-proxy.conf'),resolve(output,'node-proxy.conf'));
writeFileSync(resolve(output,'prefixes.json'),JSON.stringify(groups,null,2));
writeFileSync(resolve(output,'build-provenance.json'),JSON.stringify({sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),resourceExploration:resources},null,2));
const sourcePaths=[execFileSync('git',['diff','--name-only','--diff-filter=ACMRT','743a01304','--','.'],{cwd:root,encoding:'utf8'}),
  execFileSync('git',['ls-files','--others','--exclude-standard'],{cwd:root,encoding:'utf8'})].join('\n');
const sources=[...new Set(sourcePaths.trim().split(/\r?\n/))]
  .filter(path=>path&&!path.startsWith('chatchat-api/src/main/resources/static/')&&!path.endsWith('.log'));
const sourceHashes={};
for(const source of sources){try{if(statSync(resolve(root,source)).isFile()){copy(resolve(root,source),resolve(output,'source',source));sourceHashes[source]=createHash('sha256').update(readFileSync(resolve(root,source))).digest('hex');}}catch{}}
writeFileSync(resolve(output,'source-sha256.json'),JSON.stringify(sourceHashes,null,2));
const archive=resolve(root,resources?'target/resource-exploration-release.zip':'target/knowledge-exploration-release.zip');
execFileSync('tar',['-a','-cf',archive,'-C',output,'.']);
console.log(JSON.stringify({archive,sha256:createHash('sha256').update(readFileSync(archive)).digest('hex'),sourceFiles:Object.keys(sourceHashes).length}));
