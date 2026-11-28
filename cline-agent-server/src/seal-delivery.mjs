import { createWriteStream } from 'node:fs';
import { open, rename, rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { Journal } from './journal.mjs';

function conflict(message) { return Object.assign(Error(message), {status:409}); }

// Each committed checkpoint is sufficient for the next phase. Recovery here
// does not start or resume an Agent turn.
export async function sealDelivery(record, version, actions) {
 let progress = record.sealProgress;
 if (progress?.version !== undefined && progress.version !== version) {
  throw conflict('Delivery version immutable');
 }
 const advance = delta => {
  if (record.revoked) throw conflict('Task revoked while sealing');
  actions.checkpoint(record, delta);
  progress = record.sealProgress;
 };
 if (!progress) {
  const state = await actions.nativeStatus();
  if (state !== 'turn_completed' && state !== 'sealed') throw conflict('Native turn must complete before sealing');
  advance({sealProgress:{version}});
 }
 if (!progress.nativeSealed) {
  const status = await actions.nativeSeal();
  if (status !== 'sealed') throw conflict('Native seal not confirmed');
  advance({sealProgress:{...progress,nativeSealed:true}});
 }
 if (!progress.manifest) {
  const manifest = await actions.nativeDelivery(version);
  if (manifest.deliveryVersion !== version) throw conflict('Native delivery version differs');
  advance({sealProgress:{...progress,manifest}});
 }
 if (record.stopProof?.running !== false) {
  const stopProof = await actions.workerStop();
  if (stopProof?.running !== false) throw conflict('Executor stop not confirmed');
  advance({stopProof,workerState:'STOPPED'});
 }
 if (!progress.verified) {
  await actions.verify(progress.manifest,record.stopProof);
  advance({sealProgress:{...progress,verified:true}});
 }
 if (!progress.archived) {
  await actions.archive();
  advance({sealProgress:{...progress,archived:true}});
 }
 const manifest = progress.manifest;
 advance({delivery:manifest,sealProgress:null,taskState:'SEALED_PENDING_UPLOAD'});
 return manifest;
}

export async function archiveNativeJournal(root, file) {
 if (!(await stat(file)).isFile()) throw Error('Native journal is unavailable');
 const dest = join(root,'sealed-native-events.json');
 const temporary = dest + '.next';
 function* jsonEnvelope() {
  yield '{"events":[';
  let separator = '';
  for (const event of new Journal(file).readAfter()) {
   yield separator + JSON.stringify(event);
   separator = ',';
  }
  yield ']}';
 }
 try {
  await rm(temporary,{force:true});
  await pipeline(Readable.from(jsonEnvelope()),createWriteStream(temporary,{flags:'wx',mode:0o600}));
  const descriptor = await open(temporary,'r+');
  try { await descriptor.sync(); } finally { await descriptor.close(); }
  await rename(temporary,dest);
  if(process.platform!=='win32'){
   const dir = await open(root,'r');
   try { await dir.sync(); } finally { await dir.close(); }
  }
 } finally { await rm(temporary,{force:true}); }
}
