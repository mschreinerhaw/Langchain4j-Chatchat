<template>
  <div
    class="app-notice"
    :class="'app-notice-' + kind"
    :role="kind === 'error' ? 'alert' : 'status'"
  >
    <CircleAlert v-if="kind === 'error'" :size="16" /><CircleCheck
      v-else-if="kind === 'success'"
      :size="16"
    /><Info v-else :size="16" /> <span><slot /></span
    ><button v-if="dismissible" type="button" aria-label="关闭提示" @click="$emit('close')"
      ><X :size="15"
    /></button>
  </div>
</template>
<script src="../js/components/AppNotice.js"></script>
<style src="../styles/components/app-feedback.css"></style>
