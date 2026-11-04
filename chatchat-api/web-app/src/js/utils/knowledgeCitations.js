// Only opaque references become clickable. Resolution always rechecks live server authorization.
export function linkKnowledgeCitations(content = '') {
  return String(content).replace(/\]\(runtime-evidence:knowledge:([0-9a-f]{64})\)/g, '](#knowledge-evidence-$1)');
}
export function knowledgeCitationId(href = '') {
  return /^#knowledge-evidence-([0-9a-f]{64})$/.exec(String(href))?.[1] || '';
}
