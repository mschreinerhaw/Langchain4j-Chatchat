<template>
  <div ref="root" class="sql-search-select" :class="{ 'is-open': open, 'is-disabled': disabled }">
    <div class="sql-search-select-field">
      <input ref="input" role="combobox" type="text" autocomplete="off" :aria-label="label"
        :aria-expanded="open" :aria-controls="listId" aria-autocomplete="list"
        :aria-activedescendant="open && active >= 0 ? `${listId}-${active}` : undefined"
        :disabled="disabled" :placeholder="placeholder" :value="open ? query : selectedLabel" :data-selected-value="modelValue"
        :title="selectedLabel" @focus="show" @click="show" @input="search($event.target.value)" @keydown="keyDown" />
      <button type="button" :disabled="disabled" :aria-label="`展开${label}`" tabindex="-1"
        @mousedown.prevent @click="toggle"><ChevronDown :size="14" /></button>
    </div>
    <div v-if="open && !disabled" class="sql-search-select-menu">
      <div :id="listId" role="listbox" :aria-label="label">
        <button v-for="(option, index) in filtered" :id="`${listId}-${index}`" :key="option.value"
          type="button" role="option" :aria-selected="option.value === modelValue" :data-value="option.value"
          :class="{ active: index === active }" @mousedown.prevent @click="choose(option)"
          @mousemove="active = index"><span>{{ option.label }}</span><Check v-if="option.value === modelValue" :size="14" /></button>
        <p v-if="!filtered.length" role="status">没有匹配项</p>
      </div>
      <button v-if="hasMore" type="button" class="sql-search-select-more" :disabled="loading" @mousedown.prevent @click="$emit('more')">{{ loading ? '正在加载…' : '加载更多授权选项' }}</button>
    </div>
  </div>
</template>
<script src="../../js/components/sql-studio/SqlSearchSelect.js"></script>
<style scoped src="../../styles/components/sql-studio/SqlSearchSelect.css"></style>
