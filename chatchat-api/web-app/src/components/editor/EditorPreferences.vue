<template>
  <div class="editor-preferences">
    <label>缩进<select v-model.number="tabSize" aria-label="编辑器缩进空格" @change="update"><option :value="2">2</option><option :value="4">4</option><option :value="8">8</option></select></label>
    <label><input v-model="quickSuggestions" type="checkbox" aria-label="编辑器自动补全" @change="update">自动补全</label>
  </div>
</template>
<script src="../../js/components/editor/EditorPreferences.js"></script>
<style src="../../styles/components/editor-preferences.css"></style>
