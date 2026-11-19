const labels = {minimal:"极简",low:"低",medium:"中",high:"高",xhigh:"极高"};

export function reasoningOptions(efforts) {
  const levels = Object.keys(labels).filter(level => efforts.includes(level));
  return levels.map(value => ({value, label: labels[value]}));
}

export function availableReasoning(efforts, remembered) {
  const options=reasoningOptions(efforts);
  if (!options.length) return '';
  if (options.some(option=>option.value===remembered)) return remembered;
  return efforts.includes('medium') ? 'medium' : options[0].value;
}
