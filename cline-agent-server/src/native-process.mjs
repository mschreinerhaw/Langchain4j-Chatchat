import {fork} from 'node:child_process';

// Native SDK events contain Error objects. JSON IPC turns them into {}, losing
// the actual model/tool failure before the server can archive or redact it.
export function forkNativeWorker(modulePath,args,options) {
 return fork(modulePath,args,{...options,serialization:'advanced'});
}
