import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { prepareProgram, repairSourceContext } from '../src/code-repair.js';

const source = 'value = 1\nprint(value)\n';
const hash = createHash('sha256').update(source).digest('hex');
test('repair context binds safe source locations to the exact latest execution', () => {
  const program = "texts = []\nall_refs = list(texts.keys())\n";
  const codeHash = createHash('sha256').update(program).digest('hex');
  const feedback = { codeHash, repair: { allowed: true, baseCodeHash: codeHash },
    error: { stack: [{ file: 'task_v2.py', line: 2 }, { file: 'other.py', line: 1 }],
      message: 'private execution value' }, stderr: 'private stream' };
  const context = repairSourceContext(program, feedback, 'task_v2.py');
  assert.equal(context.baseCodeSha256, codeHash);
  assert.equal(context.excerpts.length, 1);
  assert.match(context.excerpts[0].source, /2: all_refs = list\(texts.keys\(\)\)/);
  assert.doesNotMatch(JSON.stringify(context), /private|other.py/);
  assert.equal(repairSourceContext(program + '# stale', feedback, 'task_v2.py'), null);
  assert.equal(repairSourceContext(program, { ...feedback, repair: { ...feedback.repair, allowed: false } }, 'task_v2.py'), null);
  assert.equal(repairSourceContext(program, { ...feedback, repair: { allowed: true, baseCodeHash: '0'.repeat(64) } }, 'task_v2.py'), null);
});

test('repair excerpts reject invalid frames and bound long source lines', () => {
  const program = 'x'.repeat(20000);
  const codeHash = createHash('sha256').update(program).digest('hex');
  const feedback = { codeHash, repair: { allowed: true, baseCodeHash: codeHash }, error: {
    stack: [0, -1, 2, 1.5, 1].map(line => ({ file: 'task.py', line })) } };
  const context = repairSourceContext(program, feedback, 'task.py');
  assert.equal(context.excerpts.length, 1);
  assert.ok(context.excerpts[0].source.length < 520);
});
test('exact code repair preserves unrelated bytes and rejects stale or ambiguous anchors', () => {
  assert.equal(prepareProgram({ repair: { baseCodeSha256: hash,
    edits: [{ oldText: 'value = 1', newText: 'value = 2' }] } }, source), 'value = 2\nprint(value)\n');
  assert.throws(() => prepareProgram({ repair: { baseCodeSha256: '0'.repeat(64), edits: [] } }, source), /hash mismatch/);
  assert.throws(() => prepareProgram({ repair: { baseCodeSha256: hash,
    edits: [{ oldText: 'value', newText: 'field' }] } }, source), /exactly once/);
  assert.throws(() => prepareProgram({ source, repair: {} }, source), /complete source or/);
});
