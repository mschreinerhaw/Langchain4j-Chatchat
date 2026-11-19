<template>
  <button ref="trigger" v-bind="$attrs" type="button" class="toolbar-option-trigger"
    role="combobox" aria-haspopup="listbox" :aria-label="`${label}，${selectedLabel}`"
    :aria-expanded="open" :aria-controls="open ? listId : undefined"
    :aria-activedescendant="open ? `${listId}-${active}` : undefined" :disabled="unavailable"
    :title="options.length ? selectedLabel : unavailableLabel" @click="toggle" @keydown="keyDown" @blur="close">
    <slot name="icon" />
    <span>{{ label }}</span>
    <span class="toolbar-option-value">{{ shortLabel }}</span>
    <ChevronDown class="toolbar-option-chevron" :size="13" aria-hidden="true" />
  </button>
  <Teleport to="body">
    <div v-if="open" ref="menu" class="toolbar-option-menu" :style="menuPosition">
      <div :id="listId" role="listbox" :aria-label="label" :aria-describedby="hint ? `${listId}-hint` : undefined">
        <button v-for="(option, index) in options" :id="`${listId}-${index}`" :key="option.value"
          type="button" role="option" tabindex="-1" :aria-selected="index === selectedIndex"
          :class="{ 'is-active': active === index }" @mousedown.prevent @click="choose(index)">
          <span>{{ option.label }}</span><Check v-if="index === selectedIndex" :size="14" aria-hidden="true" />
        </button>
      </div>
      <p v-if="hint" :id="`${listId}-hint`" class="toolbar-option-hint">{{ hint }}</p>
    </div>
  </Teleport>
</template>

<script src="../js/components/ToolbarOptionPicker.js"></script>
<style scoped src="../styles/components/toolbar-option-picker.css"></style>
