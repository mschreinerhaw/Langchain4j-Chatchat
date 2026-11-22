<template>
  <nav class="business-side-nav" :class="{ 'is-collapsed': collapsed }" aria-label="业务工作空间">
    <button v-for="{ id, label, icon } in entries" :key="id" type="button" class="business-nav-item"
      :title="label" :aria-label="label" :aria-current="panel === id ? 'page' : undefined"
      @click="$emit('select', id)">
      <component :is="icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
      <span>{{ label }}</span>
    </button>
  </nav>
</template>

<script src="../js/components/BusinessWorkspaceNavigation.js"></script>
