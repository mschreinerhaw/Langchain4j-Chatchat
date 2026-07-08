<template>
  <el-dialog
    :model-value="open"
    :title="title"
    :width="wide ? 'min(1120px, 92vw)' : 'min(720px, 92vw)'"
    class="modal-panel"
    destroy-on-close
    append-to-body
    @close="$emit('close')"
  >
    <p v-if="subtitle" class="modal-subtitle">{{ subtitle }}</p>
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-dialog>
</template>

<script src="../scripts/components/ModalPanel.js"></script>
