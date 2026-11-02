import { ref, watch, onMounted, onBeforeUnmount } from 'vue';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/editor/editor.all';
import { conf, language } from 'monaco-editor/esm/vs/basic-languages/sql/sql';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { format } from 'sql-formatter';

// Use the same SQL language definition and editor engine as SQL Studio.
globalThis.MonacoEnvironment = {
  ...globalThis.MonacoEnvironment,
  getWorker: () => new EditorWorker(),
};
monaco.languages.register({ id: 'sql' });
monaco.languages.setLanguageConfiguration('sql', conf);
monaco.languages.setMonarchTokensProvider('sql', {
  ...language,
  tokenizer: {
    ...language.tokenizer,
    root: [
      [/\{\{\s*[A-Za-z_]\w*\s*\}\}|\$\{[^}]+\}|(?<!:):[A-Za-z_]\w*/, 'variable'],
      ...language.tokenizer.root,
    ],
  },
});

export default {
  name: 'SqlQueryEditor',
  props: {
    modelValue: { type: String, default: '' },
    readonly: { type: Boolean, default: false },
    parameters: { type: Array, default: () => [] },
  },
  emits: ['update:modelValue', 'execute', 'error', 'blur'],
  setup(props, { emit }) {
    const host = ref(null);
    let editor, model, listener, blurListener, completion, formatter;
    onMounted(() => {
      model = monaco.editor.createModel(props.modelValue, 'sql');
      editor = monaco.editor.create(host.value, {
        model, readOnly: props.readonly, ariaLabel: 'SQL 语句', theme: 'vs',
        automaticLayout: true, fontSize: 14, lineHeight: 24,
        fontFamily: 'Consolas, "Courier New", monospace',
        tabSize: 2, insertSpaces: true, detectIndentation: false, autoIndent: 'advanced',
        minimap: { enabled: false }, scrollBeyondLastLine: false,
        wordWrap: 'on', padding: { top: 12, bottom: 12 },
        lineNumbersMinChars: 3, folding: true, bracketPairColorization: { enabled: true },
        quickSuggestions: { other: true, comments: false, strings: false },
        tabCompletion: 'on', acceptSuggestionOnEnter: 'off',
        renderLineHighlight: 'line', fixedOverflowWidgets: true,
      });
      listener = model.onDidChangeContent(() => {
        if (model.getValue() !== props.modelValue) emit('update:modelValue', model.getValue());
      });
      blurListener = editor.onDidBlurEditorText(() => emit('blur'));
      completion = monaco.languages.registerCompletionItemProvider('sql', {
        triggerCharacters: ['{', ':'],
        provideCompletionItems(current, position) {
          if (current !== model || props.readonly) return { suggestions: [] };
          const word = current.getWordUntilPosition(position);
          const range = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
          const prefix = current.getLineContent(position.lineNumber).slice(0, word.startColumn - 1);
          const afterColon = /:$/.test(prefix);
          const afterBraces = /\{\{$/.test(prefix);
          const keywords = afterColon || afterBraces ? [] : language.keywords.map(label => ({
            label, insertText: label, kind: monaco.languages.CompletionItemKind.Keyword, range,
          }));
          const functions = afterColon || afterBraces ? [] : language.builtinFunctions.map(label => ({
            label, insertText: label, kind: monaco.languages.CompletionItemKind.Function, range,
          }));
          const suffix = current.getLineContent(position.lineNumber).slice(word.endColumn - 1);
          const parameterRange = afterBraces ? new monaco.Range(position.lineNumber, word.startColumn - 2,
            position.lineNumber, word.endColumn + (suffix.startsWith('}}') ? 2 : 0)) : range;
          const parameters = props.parameters.filter(item => item.name).map(item => ({
            label: '{{' + item.name + '}}', insertText: afterColon ? item.name : '{{' + item.name + '}}',
            filterText: afterColon ? item.name : '{{' + item.name + '}}', range: parameterRange,
            detail: item.description || 'SQL 参数', kind: monaco.languages.CompletionItemKind.Variable,
          }));
          return { suggestions: [...keywords, ...functions, ...parameters] };
        },
      });
      formatter = monaco.languages.registerDocumentFormattingEditProvider('sql', {
        provideDocumentFormattingEdits(current) {
          if (current !== model || props.readonly) return [];
          try {
            return [{ range: current.getFullModelRange(), text: format(current.getValue(), {
              language: 'sql', keywordCase: 'upper', tabWidth: 2,
              paramTypes: { named: [':'], custom: [{ regex: '\\{\\{[^}]+\\}\\}|\\$\\{[^}]+\\}' }] },
            }) }];
          } catch {
            emit('error', new Error('当前 SQL 无法自动格式化，请检查语法。'));
            return [];
          }
        },
      });
      editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => emit('execute'));
    });
    watch(() => props.modelValue, value => {
      if (model && model.getValue() !== value) model.setValue(value);
    });
    watch(() => props.readonly, value => editor?.updateOptions({ readOnly: value }));
    onBeforeUnmount(() => {
      listener?.dispose(); blurListener?.dispose(); completion?.dispose(); formatter?.dispose();
      editor?.dispose(); model?.dispose();
    });
    return { host };
  },
};
