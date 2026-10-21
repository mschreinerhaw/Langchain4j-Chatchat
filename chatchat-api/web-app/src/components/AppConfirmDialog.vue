<template>
  <Teleport to="body"
    ><dialog
      ref="dialog"
      class="app-confirm-dialog"
      aria-labelledby="app-confirm-title"
      @cancel.prevent="$emit('cancel')"
    >
      <h2 id="app-confirm-title">{{ title }}</h2
      ><p>{{ message }}</p>
      <footer
        ><button type="button" autofocus @click="$emit('cancel')">取消</button
        ><button type="button" class="app-confirm-danger" @click="$emit('confirm')"
          >确认删除</button
        ></footer
      >
    </dialog></Teleport
  >
</template>
<script src="../js/components/AppConfirmDialog.js"></script>
<style src="../styles/components/app-feedback.css"></style>
