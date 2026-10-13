import { format as formatSql } from 'sql-formatter';
import { formatPythonSource } from '../utils/pythonWorkbench';

const DIALECT_FORMAT = { spark: 'spark', hive: 'hive', trino: 'trino', mysql: 'mysql', postgresql: 'postgresql', oracle: 'plsql', tsql: 'transactsql', ansi: 'sql' };
export const SQL_DIALECTS = Object.freeze(Object.keys(DIALECT_FORMAT));
export const SQL_KEYWORDS = Object.freeze(['SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'GROUP BY', 'ORDER BY', 'HAVING', 'INSERT INTO', 'CREATE TABLE', 'WITH', 'AS', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'LIMIT', 'ON', 'AND', 'OR', 'UNION ALL']);

export function editorPreferences(identity, language, storage = globalThis.localStorage) {
  const user = identity?.userId || identity?.user?.id || 'anonymous';
  const tenant = identity?.tenantId || identity?.tenant?.id || 'default';
  const key = `code-intelligence:v1:${encodeURIComponent(tenant)}:${encodeURIComponent(user)}:${language}`;
  let saved = {};
  try { saved = JSON.parse(storage?.getItem(key) || '{}') || {}; } catch { /* invalid or unavailable local storage */ }
  return {
    key,
    tabSize: [2, 4, 8].includes(saved.tabSize) ? saved.tabSize : language === 'python' ? 4 : 2,
    quickSuggestions: saved.quickSuggestions !== false,
    keywordCase: ['upper', 'lower', 'preserve'].includes(saved.keywordCase) ? saved.keywordCase : 'upper'
  };
}

export function saveEditorPreferences(preferences, storage = globalThis.localStorage) {
  const { key, tabSize, quickSuggestions, keywordCase } = preferences;
  storage?.setItem(key, JSON.stringify({ tabSize, quickSuggestions, keywordCase }));
}

export function editorLanguageOptions(language, preferences) {
  return {
    tabSize: preferences.tabSize,
    insertSpaces: true,
    detectIndentation: false,
    autoIndent: 'advanced',
    tabCompletion: 'on',
    acceptSuggestionOnEnter: 'off',
    quickSuggestions: preferences.quickSuggestions ? { other: true, comments: false, strings: false } : false,
    suggestOnTriggerCharacters: true,
    formatOnPaste: false,
    // Monaco's built-in Enter/Tab handlers retain the user's newline and indentation behavior.
    language
  };
}

export function getLanguageAdapter(language) {
  if (language === 'python') return {
    language,
    options: editorLanguageOptions,
    format: source => formatPythonSource(source)
  };
  if (language === 'sql') return {
    language,
    options: editorLanguageOptions,
    format: (source, { dialect = 'spark', preferences = {} } = {}) => formatSql(source, {
      language: DIALECT_FORMAT[dialect] || 'sql',
      tabWidth: preferences.tabSize || 2,
      keywordCase: preferences.keywordCase || 'upper'
    }),
    complete: schema => [...SQL_KEYWORDS.map(label => ({ label, category: 'keyword' })), ...schema.map(label => ({ label, category: 'field' }))]
  };
  throw new Error(`Unsupported editor language: ${language}`);
}

export function pythonBlockIndent(linePrefix, tabSize = 4) {
  if (!/^\s*(?:async\s+)?(?:def|class|for|if|elif|else|while|try|with|finally|except|match|case)\b.*:\s*$/.test(linePrefix)) return null;
  return (linePrefix.match(/^\s*/)?.[0] || '').replace(/\t/g, ' '.repeat(tabSize)) + ' '.repeat(tabSize);
}

// Only selected Mapping files returned by the authorized project-file API may contribute names.
// This deliberately does not inspect historical examples or any remote database catalog.
export function mappingSchemaColumns(files, selectedIds) {
  const selected = new Set(selectedIds);
  const names = new Set();
  for (const file of files) {
    if (file.kind !== 'MAPPING' || !selected.has(file.id)) continue;
    const source = file.draft ?? file.content ?? '';
    if (typeof source !== 'string' || source.length > 200_000) continue;
    try {
      const object = JSON.parse(source);
      const rows = Array.isArray(object) ? object : Array.isArray(object.mappings) ? object.mappings : Array.isArray(object.columns) ? object.columns : [];
      for (const row of rows) {
        for (const key of ['sourceColumn', 'targetColumn', 'source_column', 'target_column', 'source', 'target', 'column', 'field']) {
          const value = row?.[key];
          const column = typeof value === 'string' ? value.split('.').at(-1) : '';
          if (/^[A-Za-z_][\w$]*$/.test(column)) names.add(column);
        }
      }
    } catch {
      const lines = source.split(/\r?\n/).slice(0, 1000);
      const header = lines.shift()?.split(',').map(item => item.trim().toLowerCase()) || [];
      const indices = header.map((item, index) => /^(source|target)([_ ]?(column|field))?$/.test(item) ? index : -1).filter(index => index >= 0);
      for (const line of lines) for (const index of indices) {
        const value = line.split(',')[index]?.trim().split('.').at(-1);
        if (value && /^[A-Za-z_][\w$]*$/.test(value)) names.add(value);
      }
    }
  }
  return [...names].sort();
}

export function applyFormatIfUnchanged(editor, model, initialVersion, initialSource, formatted) {
  if (!editor || editor.getModel() !== model || model.isDisposed() || model.getVersionId() !== initialVersion || model.getValue() !== initialSource) return false;
  if (formatted === initialSource) return true;
  editor.pushUndoStop();
  editor.executeEdits('code-intelligence-format', [{ range: model.getFullModelRange(), text: formatted, forceMoveMarkers: true }]);
  editor.pushUndoStop();
  return true;
}
