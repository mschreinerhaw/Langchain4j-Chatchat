// A lightweight navigation outline, not a Python parser or language server.
export function pythonOutline(source = '') {
  const symbols = [];
  let quote = '';
  String(source).split('\n').forEach((line, index) => {
    let code = '';
    for (let i = 0; i < line.length;) {
      if (quote) {
        const end = line.indexOf(quote, i);
        if (end < 0) return;
        i = end + 3; quote = ''; continue;
      }
      if (line[i] === '#') break;
      const triple = line.slice(i, i + 3);
      if (triple === '"""' || triple === "'''") { quote = triple; i += 3; continue; }
      if (line[i] === '"' || line[i] === "'") {
        const single = line[i++];
        while (i < line.length) { if (line[i] === '\\') { i += 2; continue; } if (line[i++] === single) break; }
        code += ' '; continue;
      }
      code += line[i++];
    }
    const match = /^(\s*)(?:async\s+)?(def|class)\s+([\p{L}_][\p{L}\p{N}_]*)\s*(?:\(|:|\[)/u.exec(code);
    if (match) symbols.push({ name:match[3], kind:match[2] === 'class' ? 'class' : 'function', line:index + 1, indent:match[1].replace(/\t/g, '    ').length });
  });
  return symbols;
}

export function searchPythonWorkspace(scripts, query, commands = [], mode = 'all') {
  const needle = String(query || '').trim().toLocaleLowerCase();
  const commandMode = mode === 'commands' || needle.startsWith('>');
  const text = needle.replace(/^>\s*/, '');
  if (commandMode) return commands.filter(command => `${command.label} ${command.keywords || ''}`.toLocaleLowerCase().includes(text)).map(command => ({...command,kind:'command'}));
  const results = [];
  for (const script of scripts) {
    if (!text || `${script.fileName} ${script.title || ''}`.toLocaleLowerCase().includes(text)) results.push({kind:'file',script,line:1,label:script.fileName,detail:script.title || 'Python 脚本'});
    if (text && mode !== 'files') String(script.sourceCode || '').split('\n').forEach((line, index) => {
      if (line.toLocaleLowerCase().includes(text) && results.length < 60) results.push({kind:'symbol',script,line:index + 1,label:line.trim().slice(0,160),detail:`${script.fileName}:${index + 1}`});
    });
    if (results.length >= 60) break;
  }
  return results.slice(0,60);
}

export function pythonSuggestionIsCurrent(context, activeKey, source) {
  return Boolean(context && context.key === activeKey && context.source === source);
}
