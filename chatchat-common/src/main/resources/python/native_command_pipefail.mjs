/** Versioned wrapper; the original SDK helper remains byte-for-byte unchanged. */
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const spec=JSON.parse(Buffer.from(process.argv[2],'base64').toString('utf8'));
if(spec.executorPolicy!=='BASH_PIPEFAIL_V1')throw Error('NATIVE_EXECUTOR_POLICY_CHANGED');
const base=await readFile(new URL('./native_command.mjs',import.meta.url));
if(createHash('sha256').update(base).digest('hex')!==spec.baseHelperSha256)throw Error('NATIVE_EXECUTOR_VERSION_CHANGED');
process.env.SHELLOPTS=[...new Set([...(process.env.SHELLOPTS??'').split(':').filter(Boolean),'pipefail'])].join(':');
await import('./native_command.mjs');
