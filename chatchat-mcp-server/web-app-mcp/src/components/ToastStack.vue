<template>
  <div class="toast-stack">
    <article v-for="toast in toasts" :key="toast.id" class="app-toast" :class="`toast-${toast.type || 'info'}`">
      <strong>{{ toast.title }}</strong>
      <span v-if="toast.message">{{ toast.message }}</span>
    </article>
  </div>
</template>

<script src="../scripts/components/ToastStack.js"></script>

