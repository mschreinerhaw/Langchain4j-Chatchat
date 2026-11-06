import { createHash } from 'node:crypto';

// Stores only hashes and lengths for the preceding request, never another transcript.
export function createRequestContextTracker() {
  let previous = new Set();
  return request => {
    const current = new Set();
    let repeatedContextBytes = 0, duplicateContextBytes = 0, newContextBytes = 0;
    let avoidableRepeatedContextBytes = 0;
    const delivered = new Set();
    for (const message of request.messages ?? []) for (const part of message.content ?? []) {
      // Exact identical read-only outputs already present in THIS request. Different input pages,
      // necessary history, system instructions, cached tokens and previous requests are not waste.
      if (part.type !== 'tool-result' || !['input_read','code_read','metadata_read','skills','read_files','skill_read'].includes(part.toolName)) continue;
      if(part.isError===true || part.output?.error)continue; // Failed attempts are necessary repair feedback.
      const encoded = JSON.stringify({ tool:part.toolName, output:part.output });
      const hash = createHash('sha256').update(encoded).digest('hex');
      if (delivered.has(hash)) avoidableRepeatedContextBytes += Buffer.byteLength(JSON.stringify(part.output) ?? '');
      delivered.add(hash);
    }
    const parts = [request.systemPrompt ?? '', request.tools ?? [],
      ...(request.messages ?? []).flatMap(message => message.content ?? [])];
    for (const part of parts) {
      const encoded = JSON.stringify(part), bytes = Buffer.byteLength(encoded ?? '');
      const hash = createHash('sha256').update(encoded ?? '').digest('hex');
      if (previous.has(hash)) repeatedContextBytes += bytes;
      else newContextBytes += bytes;
      if (current.has(hash)) duplicateContextBytes += bytes;
      current.add(hash);
    }
    previous = new Set([...current].slice(-4096));
    return { repeatedContextBytes, duplicateContextBytes, newContextBytes, avoidableRepeatedContextBytes,
      contextChangeReason: repeatedContextBytes === 0 ? 'INITIAL_OR_REPLACED_CONTEXT'
        : newContextBytes === 0 ? 'UNCHANGED_CONTEXT' : 'HISTORY_REPLAY_WITH_NEW_PARTS' };
  };
}

// Payload attribution, not provider token accounting. Never export prompt contents.
export function measureRequestContext(request, softInputTokenBudget) {
  const bytes = value => Buffer.byteLength(JSON.stringify(value) ?? '', 'utf8');
  const components = { system: bytes(request.systemPrompt ?? ''), tools: bytes(request.tools ?? []),
    task: 0, skills: 0, toolIndex: 0, code: 0, report: 0, history: 0 };
  for (const [index, message] of (request.messages ?? []).entries()) {
    for (const part of message.content ?? []) {
      const text = part.text ?? '';
      const category = ['skills','read_files','skill_read'].includes(part.toolName) ? 'skills'
        : part.toolName === 'code_read' || part.type === 'tool-call' && part.toolName === 'python_execute'
          || text.startsWith('Untrusted latest executed program') || text.startsWith('Authorized immutable source input')
          || text.startsWith('Latest program binding') ? 'code'
        : part.toolName === 'artifact_read' || part.output?.reviewedArtifact
          || text.startsWith('Complete latest Java-authorized') ? 'report'
        : index === 0 && message.role === 'user' ? 'task' : 'history';
      components[category] += bytes(part);
      // Initial catalogs share a text part with the objective. Attribute their
      // actual serialized bytes separately without copying or changing the prompt.
      if(category==='task') {
        const section=marker=>{const at=text.lastIndexOf(marker);return at<0?undefined:text.slice(at+marker.length).split('\n')[0];};
        const catalogText=section('Runtime task configuration snapshot (methodology only; never additional permissions):\n');
        try {
          const skills=JSON.parse(catalogText).skills;
          if(skills) { const size=bytes(JSON.stringify(skills))-2;components.task-=size;components.skills+=size; }
        } catch { /* no catalog in this part */ }
        const indexText=section('Authorized tool index. python_execute is already disclosed when authorized. Load other needed schemas with tool_describe in a batch.\n')
          ??section('Authorized tool index (metadata only). Load schemas with tool_describe, batching the next necessary tools. Skills remain discoverable through skill_read.\n');
        if(indexText) { const size=bytes(indexText)-2;components.task-=size;components.toolIndex+=size; }
      }
    }
  }
  const requestBytes = bytes({ systemPrompt: request.systemPrompt ?? '', messages: request.messages ?? [], tools: request.tools ?? [] });
  const attributed = Object.values(components).reduce((a, b) => a + b, 0);
  components.envelope = requestBytes - attributed;
  return { schemaVersion: 'runtime.request-context.v1', components: Object.fromEntries(
    Object.entries(components).map(([key, value]) => [key, { bytes: value, estimatedTokens: Math.ceil(value / 4) }])),
    ...Object.fromEntries(Object.entries(components).map(([key, value]) =>
      ['context' + key[0].toUpperCase() + key.slice(1) + 'Bytes', value])),
    requestBytes, skillContextRatio:requestBytes?components.skills/requestBytes:null,
    estimatedInputTokens: Math.ceil(requestBytes / 4), estimator: 'UTF8_BYTES_DIV_4_NOT_PROVIDER_USAGE',
    ...(Number.isSafeInteger(softInputTokenBudget) && softInputTokenBudget > 0
      ? { softInputTokenBudget, exceedsSoftBudget: Math.ceil(requestBytes / 4) > softInputTokenBudget } : {}) };
}
