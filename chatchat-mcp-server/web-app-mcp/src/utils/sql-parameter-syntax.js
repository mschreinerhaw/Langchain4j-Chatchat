// Recognize SQL value parameters while leaving literals, identifiers and comments intact.
export function sqlParameterTokens(value) {
  const sql = String(value || ''), tokens = [];
  for (let i = 0; i < sql.length;) {
    const c = sql[i];
    if (c === '#' || sql.startsWith('--', i)) {
      const end = sql.indexOf('\n', i);
      i = end < 0 ? sql.length : end;
      continue;
    }
    if (sql.startsWith('/*', i)) {
      let depth = 1;
      i += 2;
      while (i < sql.length && depth) {
        if (sql.startsWith('/*', i)) { depth++; i += 2; }
        else if (sql.startsWith('*/', i)) { depth--; i += 2; }
        else i++;
      }
      continue;
    }
    if (['\'', '"', '`', '['].includes(c)) {
      const start = i, close = c === '[' ? ']' : c;
      i++;
      while (i < sql.length) {
        if (sql[i] === '\\') { i += 2; continue; }
        if (sql[i++] === close) {
          if (sql[i] === close) { i++; continue; }
          break;
        }
      }
      if ((c === '\'' || c === '"') && sql[i - 1] === close) {
        const match = /^\{\{\s*([A-Za-z_]\w*)\s*}}$/.exec(sql.slice(start + 1, i - 1));
        if (match) tokens.push({ start, end: i, name: match[1] });
      }
      continue;
    }
    const dollar = /^\$(?:[A-Za-z_]\w*)?\$/.exec(sql.slice(i));
    if (dollar) {
      const end = sql.indexOf(dollar[0], i + dollar[0].length);
      i = end < 0 ? sql.length : end + dollar[0].length;
      continue;
    }
    const token = /^\{\{\s*([A-Za-z_]\w*)\s*}}/.exec(sql.slice(i))
      || (sql[i - 1] !== ':' && /^:([A-Za-z_]\w*)/.exec(sql.slice(i)));
    if (token) {
      tokens.push({ start: i, end: i + token[0].length, name: token[1] });
      i += token[0].length;
    } else i++;
  }
  return tokens;
}

export function normalizeSqlParameters(value) {
  const sql = String(value || '');
  let result = '', cursor = 0;
  for (const token of sqlParameterTokens(sql)) {
    result += sql.slice(cursor, token.start) + '{{' + token.name + '}}';
    cursor = token.end;
  }
  return result + sql.slice(cursor);
}
