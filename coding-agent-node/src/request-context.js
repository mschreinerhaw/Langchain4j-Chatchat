// Payload attribution, not provider token accounting. Never export prompt contents.
export function measureRequestContext(request, softInputTokenBudget) {
  const bytes = value => Buffer.byteLength(JSON.stringify(value) ?? '', 'utf8');
  const components = { system: bytes(request.systemPrompt ?? ''), tools: bytes(request.tools ?? []),
    task: 0, skills: 0, code: 0, report: 0, history: 0 };
  for (const [index, message] of (request.messages ?? []).entries()) {
    for (const part of message.content ?? []) {
      const text = part.text ?? '';
      const category = part.toolName === 'skill_read' ? 'skills'
        : part.toolName === 'code_read' || part.type === 'tool-call' && part.toolName === 'python_execute'
          || text.startsWith('Untrusted latest executed program') || text.startsWith('Authorized immutable source input')
          || text.startsWith('Latest program binding') ? 'code'
        : part.toolName === 'artifact_read' || part.output?.reviewedArtifact
          || text.startsWith('Complete latest Java-authorized') ? 'report'
        : index === 0 && message.role === 'user' ? 'task' : 'history';
      components[category] += bytes(part);
    }
  }
  const requestBytes = bytes({ systemPrompt: request.systemPrompt ?? '', messages: request.messages ?? [], tools: request.tools ?? [] });
  const attributed = Object.values(components).reduce((a, b) => a + b, 0);
  components.envelope = requestBytes - attributed;
  return { schemaVersion: 'runtime.request-context.v1', components: Object.fromEntries(
    Object.entries(components).map(([key, value]) => [key, { bytes: value, estimatedTokens: Math.ceil(value / 4) }])),
    ...Object.fromEntries(Object.entries(components).map(([key, value]) =>
      ['context' + key[0].toUpperCase() + key.slice(1) + 'Bytes', value])),
    requestBytes, estimatedInputTokens: Math.ceil(requestBytes / 4), estimator: 'UTF8_BYTES_DIV_4_NOT_PROVIDER_USAGE',
    ...(Number.isSafeInteger(softInputTokenBudget) && softInputTokenBudget > 0
      ? { softInputTokenBudget, exceedsSoftBudget: Math.ceil(requestBytes / 4) > softInputTokenBudget } : {}) };
}
