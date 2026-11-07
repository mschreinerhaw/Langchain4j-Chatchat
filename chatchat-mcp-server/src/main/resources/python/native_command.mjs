/** Trusted sandbox helper. SDK shell runs only inside the managed no-network container. */
import {createShellExecutor,createDefaultTools,CommandExitError} from '/srv/coding-agent/node_modules/@cline/sdk/dist/index.js';
import {writeFile,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const spec=JSON.parse(Buffer.from(process.argv[2],'base64').toString('utf8'));
for(const name of ['sdk','core','agents','llms','shared']){
  const pkg=JSON.parse(await readFile('/srv/coding-agent/node_modules/@cline/'+name+'/package.json','utf8'));
  if(pkg.version!=='0.0.93')throw Error('NATIVE_SDK_VERSION_CHANGED');
}
if(typeof spec.toolInputJson!=='string'||Buffer.byteLength(spec.toolInputJson)>64000
    ||! /^[a-f0-9]{64}$/.test(spec.manifestHash)||!Number.isSafeInteger(spec.timeoutMs)||spec.timeoutMs<1||spec.timeoutMs>300000
    ||!Number.isSafeInteger(spec.commandOrdinal)||spec.commandOrdinal<1||spec.commandOrdinal>16)
  throw Error('Invalid Runtime command specification');
const sha=value=>createHash('sha256').update(value).digest('hex');
const canonical=value=>typeof value==='string'?JSON.stringify(value):Array.isArray(value)?'['+value.map(canonical).join(',')+']':
  value&&typeof value==='object'?'{'+Object.keys(value).sort().map(key=>JSON.stringify(key)+':'+canonical(value[key])).join(',')+'}':JSON.stringify(value);
if(sha(spec.toolInputJson)!==spec.intentHash)throw Error('NATIVE_COMMAND_INTENT_CHANGED');
const normalized=[];
const native=createDefaultTools({cwd:'/workspace/code',shell:'bash',executors:{bash:async command=>{normalized.push(command);return '';}},
  enableBash:true,enableEditor:false,enableApplyPatch:false,enableReadFiles:false,enableSearch:false,
  enableWebFetch:false,enableSkills:false,enableAskQuestion:false,enableSubmitAndExit:false});
const validation=await native.find(tool=>tool.name==='run_commands').execute(JSON.parse(spec.toolInputJson),{toolCallId:'runtime-native-validate'});
if(validation.some(value=>value.success===false)||spec.commandOrdinal>normalized.length)throw Error('NATIVE_COMMAND_SCHEMA_INVALID');
const selected=normalized[spec.commandOrdinal-1],commandHash=sha(typeof selected==='string'?selected:canonical(selected));
if(commandHash!==spec.commandHash)throw Error('NATIVE_COMMAND_NORMALIZATION_CHANGED');
const output={stdout:'',stderr:'',truncated:false,droppedChars:0};
const command=createShellExecutor({shell:'/bin/bash',timeoutMs:spec.timeoutMs,maxOutputChars:48000});
let exitCode=1,failure=null;
try{
  await command(selected,'/workspace/code',{toolCallId:'runtime-native-command',
    emitUpdate(update){
      if(!['stdout','stderr'].includes(update.stream)||typeof update.chunk!=='string')return;
      const space=Math.max(0,262144-output[update.stream].length);
      output[update.stream]+=update.chunk.slice(0,space);
      if(update.truncated||update.chunk.length>space){output.truncated=true;output.droppedChars+=(update.droppedChars??0)+Math.max(0,update.chunk.length-space);}
    }});
  exitCode=0;
}catch(error){exitCode=error instanceof CommandExitError?error.exitCode:1;failure=String(error.message).slice(0,1024);}
const receipt={manifestHash:spec.manifestHash,commandHash,intentHash:spec.intentHash,commandOrdinal:spec.commandOrdinal,
  exitCode,sdkVersion:'0.0.93',nodeVersion:process.version,...output,...(failure?{failure}:{})};
await writeFile('/workspace/output/results/native-command.json',JSON.stringify(receipt));
await Promise.all([new Promise(resolve=>process.stdout.write(output.stdout,resolve)),new Promise(resolve=>process.stderr.write(output.stderr,resolve))]);
// SDK 0.0.93 leaves losing timeout handles; all shell work and evidence writes above have settled.
process.exit(exitCode);
