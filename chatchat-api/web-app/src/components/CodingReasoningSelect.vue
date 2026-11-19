<template>
  <ToolbarOptionPicker v-if="toolbar" v-bind="$attrs" class="reasoning-toolbar-picker" label="思考强度"
    :options="options" :value="value" :disabled="disabled" :unavailable-label="unavailableLabel" @change="$emit('change', $event)">
    <template #icon><SlidersHorizontal :size="15" aria-hidden="true" /></template>
  </ToolbarOptionPicker>
  <label v-else v-bind="$attrs" class="coding-reasoning-select">
    <span>思考强度</span>
    <select aria-label="&#24605;&#32771;&#24378;&#24230;" :value="value || ''" :disabled="disabled || !options.length"
      :title="options.length ? undefined : unavailableLabel" @change="$emit('change', $event.target.value)">
      <option v-if="!options.length" value="">{{ unavailableLabel }}</option>
      <option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
  </label>
</template>

<script src="../js/components/CodingReasoningSelect.js"></script>

<style scoped src="../styles/components/coding-reasoning.css"></style>
