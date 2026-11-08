import {createRequire} from 'node:module';
import {resolve,join} from 'node:path';
import {validateRuntime} from '../src/runtime-assets.mjs';
const root=resolve(process.argv[2]??'/poc/runtime');
const assets=validateRuntime(root),require=createRequire(join(root,'package.json'));
const Database=require('better-sqlite3'),database=new Database(':memory:');
const sqlite=database.prepare('select sqlite_version() as version').get();database.close();
console.log(JSON.stringify({...assets,node:process.version,nodeABI:process.versions.modules,sqlite:sqlite.version,status:'PASS'}));
