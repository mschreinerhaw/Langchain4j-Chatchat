import { ref } from 'vue';
import { editorPreferences, saveEditorPreferences } from '../../editor/codeIntelligence';

export default {
  props: { workspaceContext: Object, language: { type: String, required: true } },
  emits: ['change'],
  setup(props, { emit }) {
    const initial = editorPreferences(props.workspaceContext, props.language);
    const tabSize = ref(initial.tabSize);
    const quickSuggestions = ref(initial.quickSuggestions);
    function update() {
      const next = { ...initial, tabSize: tabSize.value, quickSuggestions: quickSuggestions.value };
      try { saveEditorPreferences(next); } catch { /* Storage quota cannot interrupt editing. */ }
      emit('change', next);
    }
    return { tabSize, quickSuggestions, update };
  }
};
