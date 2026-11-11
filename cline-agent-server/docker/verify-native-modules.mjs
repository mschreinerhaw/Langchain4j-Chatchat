import {readdirSync} from 'node:fs';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';
const root=process.argv[2], files=[];
function walk(dir){for(const e of readdirSync(dir,{withFileTypes:true})){const p=join(dir,e.name);if(e.isDirectory())walk(p);else if(e.isFile()&&e.name.endsWith('.node'))files.push(p);}}
walk(root);
for(const p of files){const r=spawnSync('ldd',[p],{encoding:'utf8'});if(r.status!==0||r.stdout.includes('not found'))throw Error(`Native ABI dependencies: ${p}: ${r.stdout} ${r.stderr}`);}
const require=createRequire(join(root,'package.json'));
const Database=require('better-sqlite3');
const db=new Database(':memory:');
if(db.prepare('select 41+1 as value').get().value!==42)throw Error('SQLite execution failed');
db.close();
console.log(JSON.stringify({node:process.version,abi:process.versions.modules,nativeModules:files.length,sqliteExecuted:true}));
