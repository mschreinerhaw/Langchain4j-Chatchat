<template>
  <Teleport to="body"
    ><dialog
      ref="dialog"
      class="app-confirm-dialog"
      aria-labelledby="app-confirm-title"
      :aria-busy="busy"
      @cancel.prevent="cancel"
    >
      <h2 id="app-confirm-title">{{ title }}</h2
      ><slot
        ><p>{{ message }}</p></slot
      >
      <p v-if="error" class="app-confirm-error" role="alert">{{ error }}</p>
      <footer
        ><button type="button" autofocus :disabled="busy" @click="cancel">{{ cancelLabel }}</button
        ><button
          type="button"
          class="app-confirm-danger"
          :disabled="busy || !!error"
          @click="confirm"
          >{{ busy ? "正在删除…" : "确认删除" }}</button
        ></footer
      >
    </dialog></Teleport
  >
</template>
<script src="../js/components/AppConfirmDialog.js"></script>
<style src="../styles/components/app-feedback.css"></style>
