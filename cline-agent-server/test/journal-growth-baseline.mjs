// Run with node --expose-gc. Fault-free isolated storage test of the existing Journal.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, rmdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { Journal } from '../src/journal.mjs';

const directory = mkdtempSync(join(tmpdir(), 'cline-journal-baseline-'));
const file = join(directory, 'events.jsonl');
const contents = 'x'.repeat(16_384);
const results = [];
try {
  let journal = new Journal(file);
  for (const count of [64, 128, 256]) {
    const previousBytes = results.at(-1)?.bytes ?? 0;
    const start = performance.now();
    for (let index = 0; index < count; index++) {
      journal.append({ kind: 'test-retry', attemptId: 'same-fixture', contents });
    }
    const appendMs = Math.round(performance.now() - start);
    const bytes = statSync(file).size;
    journal = undefined;
    global.gc?.();
    const heapBefore = process.memoryUsage().heapUsed;
    const replay = performance.now();
    journal = new Journal(file);
    const replayMs = Math.round(performance.now() - replay);
    const heapDeltaBytes = process.memoryUsage().heapUsed - heapBefore;
    const cursor = String(journal.events.length - 1);
    assert.equal(journal.after(cursor).length, 1);
    assert.equal(statSync(file).size, bytes);
    results.push({ appended: count, events: journal.events.length, bytes, growthBytes: bytes - previousBytes,
      appendMs, replayMs, heapDeltaBytes });
  }
  console.log(JSON.stringify(results, null, 2));
} finally {
  rmSync(file, { force: true });
  rmdirSync(directory);
}
