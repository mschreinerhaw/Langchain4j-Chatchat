import { createHash } from 'node:crypto';

/** Code-only edits against one exact prior program; never reads files or source data. */
export function prepareProgram(input, previous) {
  if (typeof input?.source === 'string' && input.repair === undefined) {
    if (!input.source.trim()) throw new TypeError('source is required');
    return bounded(input.source);
  }
  if (input?.source !== undefined || !input?.repair || typeof previous !== 'string')
    throw new TypeError('Provide complete source or a repair against the previous program');
  const { baseCodeSha256, edits } = input.repair;
  if (baseCodeSha256 !== createHash('sha256').update(previous).digest('hex'))
    throw new TypeError('Repair base code hash mismatch');
  if (!Array.isArray(edits) || edits.length < 1 || edits.length > 32)
    throw new TypeError('Repair requires 1..32 exact edits');
  let source = previous;
  for (const edit of edits) {
    if (typeof edit.oldText !== 'string' || !edit.oldText || edit.oldText.length > 16000
        || typeof edit.newText !== 'string' || edit.newText.length > 16000)
      throw new TypeError('Invalid bounded repair edit');
    const offset = source.indexOf(edit.oldText);
    if (offset < 0 || source.lastIndexOf(edit.oldText) !== offset)
      throw new TypeError('Repair anchor must match exactly once');
    source = bounded(source.slice(0, offset) + edit.newText + source.slice(offset + edit.oldText.length));
  }
  if (source === previous) throw new TypeError('Repair did not change the program');
  return source;
}

function bounded(source) {
  if (Buffer.byteLength(source, 'utf8') > 256000) throw new TypeError('Python source exceeds Runtime limit');
  return source;
}
