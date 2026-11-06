import { createDefaultTools } from '@cline/core';
import { readFile, writeFile } from 'node:fs/promises';
const metadata=JSON.parse(await readFile(new URL('../node_modules/@cline/core/package.json',import.meta.url),'utf8'));
const tools=createDefaultTools({executors:{skills:async()=>'',readFile:async()=>''},enableSkills:true,enableReadFiles:true,
  enableBash:false,enableSearch:false,enableWebFetch:false,enableEditor:false,enableApplyPatch:false,enableAskQuestion:false,enableSubmitAndExit:false});
const snapshot=JSON.stringify({sdkVersion:metadata.version,source:'@cline/core public createDefaultTools',
  tools:tools.map(({name,description,inputSchema})=>({name,description,inputSchema}))},null,2)+'\n';
if(process.argv[2])await writeFile(process.argv[2],snapshot);else process.stdout.write(snapshot);
