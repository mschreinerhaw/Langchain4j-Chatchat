/** Package public worker runtime and all isolated-test dependencies, never credentials. */
import {stat,readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
const output=resolve(process.argv[2]||'target/native-remediation-worker.tar.gz');
const entries=['coding-agent-node/dist','coding-agent-node/src','coding-agent-node/test','coding-agent-node/fixtures',
 'coding-agent-node/integration','coding-agent-node/scripts','coding-agent-node/skills','coding-agent-node/package.json',
 'chatchat-common/src/main/resources/runtime/coding','scripts/test-cline-sdk-sandbox.py'];
for(const path of [...entries,'coding-agent-node/integration/sdk-sandbox.mjs',
 'chatchat-common/src/main/resources/runtime/coding/python-project-adapter.py',
 'chatchat-common/src/main/resources/runtime/coding/python-project-adapter.legacy-output-v1.py'])await stat(path);
execFileSync('tar',['-czf',output,...entries],{stdio:'inherit'});
const data=await readFile(output),proof={archiveSha256:createHash('sha256').update(data).digest('hex'),bytes:data.length,
 entries,sourceCommit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim(),scope:'Public source and runtime only; contains no node_modules or deployment credentials.'};
await writeFile(output+'.json',JSON.stringify(proof,null,2)+'\n');process.stdout.write(JSON.stringify(proof)+'\n');
