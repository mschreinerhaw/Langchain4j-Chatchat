<template>
  <section class="panel">
    <header v-if="title || $slots.actions" class="panel-header">
      <div>
        <h2>{{ title }}</h2>
        <p v-if="description" class="muted">{{ description }}</p>
      </div>
      <slot name="actions" />
    </header>
    <div class="panel-body"><slot /></div>
  </section>
</template>
<script setup>
  defineProps({ title: String, description: String });
</script>
