// The 0.0.93 root package does not export its model routing helper.
// Reuse that exact upstream source rather than inventing another routing policy.
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import ts from 'typescript';
const root=new URL('../',import.meta.url);
const sourceUrl=new URL('vendor/cline-0.0.93/model-tool-routing.ts',root);
const metadata=JSON.parse(readFileSync(new URL('vendor/cline-0.0.93/provenance.json',root),'utf8'));
const source=readFileSync(sourceUrl,'utf8').replace(/\r\n/g,'\n');
const digest=createHash('sha256').update(source).digest('hex');
if(digest!==metadata.sourceSha256)throw new Error('Pinned upstream routing source changed');
const compiled=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ES2022}}).outputText;
const output='// Generated from unmodified Cline 0.0.93 source; Apache-2.0.\n// Commit '+metadata.commit+'; source SHA256 '+digest+'\n'+compiled;
const target=new URL('src/cline-tool-routing.js',root);
if(process.argv.includes('--check')) {
  if(readFileSync(target,'utf8').replace(/\r\n/g,'\n')!==output)throw new Error('Generated routing module differs from pinned upstream');
} else writeFileSync(target,output);
