<template>
  <ModalPanel
    :open="open"
    :title="title"
    subtitle="请选择需要永久删除的审计日志时间范围，起止时间均包含在清理范围内。"
    @close="close"
  >
    <el-alert
      title="该操作不可恢复，请确认时间范围后再执行。"
      type="warning"
      :closable="false"
      show-icon
      class="audit-cleanup-warning"
    />
    <el-form label-position="top">
      <el-form-item label="清理时间范围" required :error="validationError">
        <el-date-picker
          v-model="timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          format="YYYY-MM-DD HH:mm:ss"
          :disabled-date="disableFutureDate"
          class="w-100"
          @change="validationError = ''"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="close">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="submit">确认清理</el-button>
    </template>
  </ModalPanel>
</template>

<script src="../scripts/components/AuditCleanupDialog.js"></script>

<style scoped>
.audit-cleanup-warning {
  margin-bottom: 18px;
}
</style>
