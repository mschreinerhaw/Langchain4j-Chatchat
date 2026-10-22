// Old manifests may mount the canonical answer next to the same visual report.
// Project the layout only: keep all immutable resources available for auditing.
export function artifactPresentationSpec(spec) {
  if (!spec?.elements || typeof spec.elements !== 'object') return spec;
  const elements = { ...spec.elements };
  let changed = false;
  for (const [id, element] of Object.entries(spec.elements)) {
    if (element?.type !== 'Report' || !Array.isArray(element.children)) continue;
    if (!element.children.some(child => elements[child]?.type === 'VisualReport')) continue;
    const children = element.children.filter(child => {
      const node = elements[child];
      return !(node?.type === 'Markdown' && node.props?.resourceId === 'answer')
        && !(node?.type === 'Html' && node.props?.resourceId === 'report');
    });
    if (children.length !== element.children.length) {
      elements[id] = { ...element, children };
      changed = true;
    }
  }
  return changed ? { ...spec, elements } : spec;
}
