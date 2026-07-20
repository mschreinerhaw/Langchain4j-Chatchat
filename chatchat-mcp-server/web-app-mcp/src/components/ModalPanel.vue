<template>
  <el-dialog
    :model-value="open"
    :title="title"
    :width="workbench ? 'min(1540px, 96vw)' : wide ? 'min(1120px, 92vw)' : 'min(720px, 92vw)'"
    :fullscreen="maximizable && maximized"
    class="modal-panel"
    :class="{ 'modal-panel--maximized': maximizable && maximized }"
    destroy-on-close
    append-to-body
    @close="handleClose"
  >
    <template #header>
      <div class="modal-panel-header">
        <span class="modal-panel-header-title">{{ title }}</span>
        <button
          v-if="maximizable"
          type="button"
          class="modal-panel-window-button"
          :title="maximized ? '还原窗口' : '最大化窗口'"
          :aria-label="maximized ? '还原窗口' : '最大化窗口'"
          @click.stop="toggleMaximized"
        >
          <span aria-hidden="true">{{ maximized ? '❐' : '□' }}</span>
          {{ maximized ? '还原' : '最大化' }}
        </button>
      </div>
    </template>
    <p v-if="subtitle" class="modal-subtitle">{{ subtitle }}</p>
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </el-dialog>
</template>

<script src="../scripts/components/ModalPanel.js"></script>
