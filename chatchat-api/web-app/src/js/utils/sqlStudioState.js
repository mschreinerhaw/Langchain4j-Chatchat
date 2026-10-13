export const dirtySqlFile = file => file.draft !== undefined && file.draft !== file.content;
export function mergeSqlFiles(incoming, current) {
  const local = new Map(current.map(file => [file.id, file]));
  return incoming.map(file => {
    const draft = local.get(file.id);
    return dirtySqlFile(draft || {}) ? { ...file, draft: draft.draft, draftBaseVersion: draft.draftBaseVersion ?? draft.version,
      conflict: file.version !== (draft.draftBaseVersion ?? draft.version) } : { ...file, draft: file.content, draftBaseVersion: file.version };
  });
}
export function restoreSqlDrafts(files, stored) {
  const permitted = new Map(files.map(file => [file.id, file]));
  for (const draft of Array.isArray(stored?.drafts) ? stored.drafts : []) {
    const file = permitted.get(draft.id);
    if (file?.editable && typeof draft.content === 'string' && draft.content.length <= 200000 && Number.isInteger(draft.baseVersion)) {
      file.draft = draft.content; file.draftBaseVersion = draft.baseVersion; file.conflict = draft.baseVersion !== file.version;
    }
  }
  return files;
}
export function sqlFileTree(files, folders = []) {
  const root = { children: [] };
  for (const folder of folders) {
    let node = root;
    folder.split('/').forEach((name, index, parts) => {
      let directory = node.children.find(child => child.children && child.name.toLowerCase() === name.toLowerCase());
      if (!directory) { directory = { name, id: parts.slice(0, index + 1).join('/'), children: [] }; node.children.push(directory); }
      node = directory;
    });
  }
  for (const file of files) {
    const parts = file.path.split('/'); let node = root;
    parts.forEach((name, index) => {
      if (index === parts.length - 1) node.children.push({ id: file.id, name, file });
      else {
        let directory = node.children.find(child => child.name.toLowerCase() === name.toLowerCase() && child.children);
        if (!directory) { directory = { name, id: parts.slice(0, index + 1).join('/'), children: [] }; node.children.push(directory); }
        node = directory;
      }
    });
  }
  return root.children;
}
export function assertSqlApplySafe(candidates, files) {
  const dirty = new Set(files.filter(dirtySqlFile).map(file => file.path.toLowerCase()));
  if (candidates.some(candidate => dirty.has(candidate.path.toLowerCase()))) throw new Error('生成结果与未保存的编辑冲突，请先保存或比较修改；不会覆盖当前内容。');
}
// A user may type while the confirmed apply request is in flight. Keep that draft
// and its original base revision so the next save requests a comparison, not an overwrite.
export function mergeAppliedSqlFile(saved, current) {
  return dirtySqlFile(current || {})
    ? { ...saved, draft:current.draft, draftBaseVersion:current.draftBaseVersion ?? current.version, conflict:true }
    : { ...saved, draft:saved.content, draftBaseVersion:saved.version, conflict:false };
}
export const terminalSqlTask = status => ['SUCCESS','PARTIAL','PARTIAL_SUCCESS','FAILED','CANCELLED','KILLED','REJECTED','EMPTY','NO_PRESENTABLE_RESULT','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED','TIMEOUT_CANCELLED','DLQ'].includes(status);
