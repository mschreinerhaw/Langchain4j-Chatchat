import assert from 'node:assert/strict';
import { closeSync, mkdtempSync, openSync, rmSync, statSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';

const megabytes = Number(process.argv[2]);
assert.ok([10, 50, 100, 500].includes(megabytes));
const module = process.argv[3] ? pathToFileURL(process.argv[3]).href : '../src/journal.mjs';
const { Journal } = await import(module);
const dir = mkdtempSync(join(process.env.JOURNAL_BENCH_ROOT ?? tmpdir(), 'cline-journal-scale-'));
const file = join(dir, 'events.jsonl');
const recordBytes = 16 * 1024;
const count = megabytes * 1024 * 1024 / recordBytes;
let maxRss = 0;
const sample = () => { maxRss = Math.max(maxRss, process.memoryUsage().rss); };

try {
  const fd = openSync(file, 'wx', 0o600);
  try {
    const pad = Buffer.alloc(recordBytes, 0x78);
    for (let i = 1; i <= count; i++) {
      const head = Buffer.from('{"eventId":"' + i + '","payload":"');
      head.copy(pad);
      pad.fill(0x78, head.length);
      pad.write('"}\n', recordBytes - 3);
      assert.equal(writeSync(fd, pad), recordBytes);
    }
  } finally { closeSync(fd); }
  assert.equal(statSync(file).size, megabytes * 1024 * 1024);
  global.gc?.();
  const initial = process.memoryUsage();
  sample();
  const initialTime = performance.now();
  const open = () => new Journal(file);
  const page = (journal, cursor) => journal.page?.(cursor, 2).events ?? journal.after(cursor).slice(0, 2);
  let journal = open();
  const openMs = performance.now() - initialTime;
  sample();
  const first = performance.now();
  assert.equal(page(journal, String(count - 1)).length, 1);
  const coldTailMs = performance.now() - first;
  sample();
  const waits = [];
  const start = performance.now();
  for (let i = 0; i < 12; i++) {
    const scheduled = performance.now();
    const tick = new Promise(resolve => setImmediate(resolve));
    if (!journal.page) {
      journal = undefined;
      global.gc?.();
      journal = open();
    }
    assert.equal(page(journal, String(count - 1)).length, 1);
    sample();
    await tick;
    waits.push(performance.now() - scheduled);
  }
  const pollingMs = performance.now() - start;
  const beginWrite = performance.now();
  for (let i = 0; i < 24; i++) journal.append({ operation: 'audit-fixture', ordinal: i });
  const appendMs = performance.now() - beginWrite;
  const batch = Array.from({length:24}, (_, ordinal)=>({operation:'audit-pair-fixture',ordinal}));
  const beginBatch = performance.now();
  for(let i=0;i<batch.length;i+=2){
    if(journal.appendBatch) journal.appendBatch(batch.slice(i,i+2));
    else for(const event of batch.slice(i,i+2))journal.append(event);
  }
  const pairedAppendMs = performance.now() - beginBatch;
  global.gc?.();
  const retained = process.memoryUsage();
  console.log(JSON.stringify({ megabytes, events: count, openMs, coldTailMs, pollingMs, appendMs,pairedAppendMs,
    maxImmediateDelayMs: Math.max(...waits), peakRssDelta: maxRss - initial.rss,
    retainedHeapDelta: retained.heapUsed - initial.heapUsed, maxRssKiB: process.resourceUsage().maxRSS }));
} finally {
  rmSync(dir, { recursive: true, force: true });
}
