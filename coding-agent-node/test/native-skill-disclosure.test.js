import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { validateNativeDisclosure } from '../src/skills.js';

test('native Claude/Codex/Copilot-style frontmatter passes the actual Cline parser without rewriting', () => {
  for (const extension of ['', 'allowed-tools: Bash\n', 'metadata:\n  author: OpenAI\n  version: "1.0"\n', 'compatibility: GitHub Copilot\n']) {
    const instructions = `---\nname: native-example\ndescription: Review supplied Python data\nlicense: MIT\n${extension}---\n# Original\r\nRead scripts/check.py and assets/chart.bin.\n`;
    const result = { nativeOriginal: true, instructions, contentSha256: createHash('sha256').update(instructions).digest('hex') };
    assert.equal(validateNativeDisclosure(result, { name: 'native-example' }), result);
    assert.equal(result.instructions, instructions);
  }
});
test('native hash mismatch and name collision cannot be silently accepted', () => {
  const instructions = '---\nname: native-example\ndescription: Review data\n---\n# Original';
  const result = { nativeOriginal: true, instructions, contentSha256: createHash('sha256').update(instructions).digest('hex') };
  assert.throws(() => validateNativeDisclosure({ ...result, contentSha256: 'wrong' }, { name: 'native-example' }), /hash mismatch/);
  assert.throws(() => validateNativeDisclosure(result, { name: 'another-name' }), /cannot be loaded/);
});
test('legacy and reference disclosures retain their existing interpretation', () => {
  const legacy = { instructions: '# Legacy', contentSha256: 'legacy' };
  assert.equal(validateNativeDisclosure(legacy, { name: 'Legacy' }), legacy);
  const reference = { nativeOriginal: true, path: 'scripts/check.py', content: 'print(1)' };
  assert.equal(validateNativeDisclosure(reference, { name: 'native-example' }), reference);
});
