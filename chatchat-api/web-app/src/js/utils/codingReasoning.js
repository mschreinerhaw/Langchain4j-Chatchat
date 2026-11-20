const labels = {minimal:"极简",low:"低",medium:"中",high:"高",xhigh:"极高"};

export function reasoningOptions(efforts) {
  const levels = Object.keys(labels).filter(level => efforts.includes(level));
  return levels.length ? [...levels.map(value => ({value, label: labels[value]})), {value:'off', label:'\u5173\u95ed'}] : [];
}

export function availableReasoning(efforts, remembered) {
  const options=reasoningOptions(efforts);
  if (!options.length) return '';
  if (remembered) return options.some(option=>option.value===remembered) ? remembered : 'off';
  return efforts.includes('medium') ? 'medium' : options[0].value;
}
