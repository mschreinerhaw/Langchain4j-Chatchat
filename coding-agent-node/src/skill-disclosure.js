import { createHash } from 'node:crypto';

const sha = value => createHash('sha256').update(value).digest('hex');
const identity = value => sha(JSON.stringify(value));

/** Task-scoped disclosure receipts, not another transcript or compaction engine.
 * Call the authorized reader BEFORE disclose, including on reuse. Only content
 * still present byte-for-byte in the actual SDK-prepared request can be reused.
 */
export function createSkillDisclosure() {
  let visible = new Map();
  const reads = new Set();
  return {
    prepare(request) {
      visible = new Map();
      for (const m of request.messages ?? []) for (const p of m.content ?? [])
        if (p.type === 'tool-result' && p.toolName === 'skill_read' && !p.isError
            && (typeof p.output?.instructions === 'string' || typeof p.output?.content === 'string'))
          visible.set(identity(p.output), p.toolCallId);
    },
    disclose(original, input) {
      let result = original;
      const path = input.path || 'SKILL.md';
      const paging = input.offset !== undefined || input.limit !== undefined;
      if (paging) {
        if (path === 'SKILL.md' || original.encoding === 'base64' || typeof original.content !== 'string')
          throw new TypeError('Bounded reading requires a UTF-8 reference file; load SKILL.md in full');
        const offset = input.offset ?? 0, limit = input.limit ?? 8000;
        if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 16000)
          throw new TypeError('Skill reference offset/limit must be bounded Unicode code-point counts');
        const points = Array.from(original.content);
        const content = points.slice(offset, offset + limit).join('');
        result = { ...original, content, offset, totalCodePoints: points.length,
          complete: offset === 0 && offset + limit >= points.length,
          nextOffset: offset + limit < points.length ? Math.min(offset + limit, points.length) : null,
          returnedContentSha256: sha(content), size: Buffer.byteLength(original.content) };
      }
      const key = identity(result), duplicateRead = reads.has(key);
      if (reads.size < 512) reads.add(key);
      const sourceText = original.instructions ?? original.content ?? '';
      const contentBytes = Buffer.byteLength(result.instructions ?? result.content ?? '');
      const reusedFrom = visible.get(key);
      return {
        result: reusedFrom ? { skillId: original.skillId, version: original.version, path,
          contentSha256: original.contentSha256 ?? original.sha256,
          reusedFromToolCallId: reusedFrom, contentAlreadyInContext: true,
          ...(paging ? { offset: result.offset, nextOffset: result.nextOffset, totalCodePoints: result.totalCodePoints } : {}),
          authority: original.authority } : result,
        observation: { skillId: original.skillId, skillVersion: original.version, skillPath: path,
          skillContentHash: original.contentSha256 ?? original.sha256 ?? sha(sourceText),
          skillSourceBytes: Buffer.byteLength(sourceText), skillContentBytes: reusedFrom ? 0 : contentBytes,
          skillEstimatedTokens: reusedFrom ? 0 : Math.ceil(contentBytes / 4),
          duplicateRead, contentReused: Boolean(reusedFrom), skillReference: path !== 'SKILL.md' },
      };
    },
  };
}
