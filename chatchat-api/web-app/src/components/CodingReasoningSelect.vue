<template>
  <label class="coding-reasoning-select" :class="{ 'reasoning-toolbar-picker': toolbar }"
    :title="toolbar && !options.length ? unavailableLabel : undefined">
    <SlidersHorizontal v-if="toolbar" :size="15" aria-hidden="true" />
    <span>思考强度</span>
    <span v-if="toolbar" class="reasoning-toolbar-value" aria-hidden="true">{{ selectedLabel }}</span>
    <ChevronDown v-if="toolbar" :size="13" aria-hidden="true" />
    <select aria-label="&#24605;&#32771;&#24378;&#24230;" :value="value || ''" :disabled="disabled || !options.length"
      :title="options.length ? undefined : unavailableLabel" @change="$emit('change', $event.target.value)">
      <option v-if="!options.length" value="">{{ unavailableLabel }}</option>
      <option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
  </label>
</template>

<script src="../js/components/CodingReasoningSelect.js"></script>

<style scoped src="../styles/components/coding-reasoning.css"></style>
