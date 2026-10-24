import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';

const [output,version,api,mcp]=process.argv.slice(2);
assert(output && version && /^[a-f0-9]{64}$/.test(api) && /^[a-f0-9]{64}$/.test(mcp));
const classes=[
  'orchestration/AgentOrchestrationEngine',
  'orchestration/InterpretationAnalysisSession',
  'orchestration/AgentWorkflowDecisionEngine',
  'orchestration/analysis/governance/AnalysisCoverageCoordinator',
  'runtime/plan/InterpretationPlanOptimizer',
  'runtime/plan/template/SqlTemplateWorkflowPlugin',
  'runtime/plan/template/SshTemplateWorkflowPlugin',
  'runtime/plan/template/TemplateWorkflowMetadata',
  'runtime/plan/template/PublishedDataAcquisitionPlanner',
].map(name=>'com/chatchat/agents/'+name+'.class');
for(const name of classes){const target=path.join(output,'classes',name);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(path.join('chatchat-agents/target/classes',name),target);}
for(const name of ['patch-published-data-supply.py','deploy-publication-workspace-ui.py','deploy-unified-management.py','application_image_build.py'])fs.copyFileSync('scripts/'+name,path.join(output,name));
fs.cpSync('deploy/docker/application',path.join(output,'templates'),{recursive:true});
fs.writeFileSync(path.join(output,'supply-manifest.json'),JSON.stringify({version,sourceCommit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),baseline:{api,mcp},classes},null,2));
console.log(JSON.stringify({output,version,classCount:classes.length}));
