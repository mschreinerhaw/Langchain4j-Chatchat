import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { prepareProgram } from '../src/code-repair.js';

const source = 'value = 1\nprint(value)\n';
const hash = createHash('sha256').update(source).digest('hex');
test('exact code repair preserves unrelated bytes and rejects stale or ambiguous anchors', () => {
  assert.equal(prepareProgram({ repair: { baseCodeSha256: hash,
    edits: [{ oldText: 'value = 1', newText: 'value = 2' }] } }, source), 'value = 2\nprint(value)\n');
  assert.throws(() => prepareProgram({ repair: { baseCodeSha256: '0'.repeat(64), edits: [] } }, source), /hash mismatch/);
  assert.throws(() => prepareProgram({ repair: { baseCodeSha256: hash,
    edits: [{ oldText: 'value', newText: 'field' }] } }, source), /exactly once/);
  assert.throws(() => prepareProgram({ source, repair: {} }, source), /complete source or/);
});
