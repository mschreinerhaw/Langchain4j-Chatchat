import { describe, it, expect } from 'vitest';
import { linkKnowledgeCitations, knowledgeCitationId } from './knowledgeCitations.js';
describe('knowledge citation navigation',()=>{
  it('uses opaque local navigation and leaves unrelated links intact',()=>{
    const id='a'.repeat(64);
    expect(linkKnowledgeCitations(`[章节](runtime-evidence:knowledge:${id}) [外部](https://example.org)`))
      .toBe(`[章节](#knowledge-evidence-${id}) [外部](https://example.org)`);
    expect(knowledgeCitationId(`#knowledge-evidence-${id}`)).toBe(id);
    for(const link of ['javascript:alert(1)','#knowledge-evidence-../secret','https://example.org'])expect(knowledgeCitationId(link)).toBe('');
  });
});
