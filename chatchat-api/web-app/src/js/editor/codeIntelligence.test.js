import { describe, expect, it } from 'vitest';
import { applyFormatIfUnchanged, editorLanguageOptions, editorPreferences, getLanguageAdapter, mappingSchemaColumns, pythonBlockIndent } from './codeIntelligence';
import { sqlDiagnostics } from './sqlDiagnostics';

describe('shared code intelligence', () => {
  it('keeps user and language preferences isolated with safe defaults', () => {
    const storage = { getItem(key) { return key.includes(':sql') ? '{"tabSize":8,"keywordCase":"lower"}' : null; } };
    expect(editorPreferences({ tenantId:'a', userId:'b' }, 'python', storage).tabSize).toBe(4);
    expect(editorPreferences({ tenantId:'a', userId:'b' }, 'sql', storage).tabSize).toBe(8);
    expect(editorLanguageOptions('python', { tabSize:4, quickSuggestions:true })).toMatchObject({ tabSize:4, autoIndent:'advanced', acceptSuggestionOnEnter:'off', insertSpaces:true });
  });

  it('formats SQL for two dialects and preserves Python fallback behavior', () => {
    const sql = getLanguageAdapter('sql');
    expect(sql.format('select a,b from t', { dialect:'spark' })).toContain('SELECT');
    expect(sql.format('select a,b from t', { dialect:'mysql' })).toContain('FROM');
    expect(getLanguageAdapter('python').format('def f():\n\treturn 1  ')).toContain('    return 1');
  });

  it('indents Python block headers with the configured tab width', () => {
    expect(pythonBlockIndent('    if True:', 4)).toBe('        ');
    expect(pythonBlockIndent('def f():', 4)).toBe('    ');
    expect(pythonBlockIndent('    pass', 4)).toBeNull();
  });

  it('only completes fields from selected authorized Mapping files', () => {
    const files = [
      { id:'mine',kind:'MAPPING',content:'[{"sourceColumn":"source_id","targetColumn":"target_id"}]' },
      { id:'csv',kind:'MAPPING',content:'source,target\na.customer_key,b.dimension_key' },
      { id:'other',kind:'MAPPING',content:'[{"sourceColumn":"secret_field"}]' },
      { id:'script',kind:'SQL',content:'select hidden_column' }
    ];
    expect(mappingSchemaColumns(files, ['mine','csv','script'])).toEqual(['customer_key','dimension_key','source_id','target_id']);
  });

  it('refuses to replace a changed model or switched tab', () => {
    let value = 'select x'; let version = 1; let edits = 0;
    const model = { isDisposed:() => false, getVersionId:() => version, getValue:() => value, getFullModelRange:() => ({}) };
    const editor = { getModel:() => model, pushUndoStop:() => {}, executeEdits:() => { edits++; } };
    version++;
    expect(applyFormatIfUnchanged(editor, model, 1, value, 'SELECT x')).toBe(false);
    expect(edits).toBe(0);
  });

  it.each(['spark', 'hive', 'trino', 'mysql', 'postgresql'])('shows %s grammar diagnostics without requiring AI', async dialect => {
    const markers = await sqlDiagnostics('select from', dialect);
    expect(markers.length).toBeGreaterThan(0);
    expect(markers[0].severity).toBe(8);
  }, 30000); // The first dialect includes the grammar's cold dynamic import on Windows.
});
