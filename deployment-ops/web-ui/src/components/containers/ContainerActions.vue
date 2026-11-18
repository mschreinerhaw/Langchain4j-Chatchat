<template>
  <div class="table-actions">
    <button
      :disabled="!allowed || (row.State !== 'exited' && row.State !== 'created')"
      :title="reason"
      @click="operate('start')"
    >
      启动
    </button>
    <button
      :disabled="!allowed || row.State !== 'running'"
      :title="reason"
      @click="operate('stop')"
    >
      停止
    </button>
    <button
      :disabled="!allowed || row.State !== 'running'"
      :title="reason"
      @click="operate('restart')"
    >
      重启
    </button>
    <button
      v-if="admin && owned(row) && !applicationComponent(row) && row.State !== 'running'"
      class="danger-button"
      @click="operate('delete')"
    >
      删除
    </button>
  </div>
  <small v-if="reason">{{ reason }}</small>
  <small v-else-if="applicationComponent(row)">
    仅操作当前组件；依赖它的服务可能受影响，请审阅确认。
  </small>
  <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
</template>
<script setup>
  import { computed, ref } from 'vue';
  import { owned, installationOwned, applicationComponent } from '../../utils/format';
  import { useSession } from '../../stores/session';
  import { useOperations } from '../../composables/useOperations';
  const props = defineProps({
    row: { type: Object, required: true },
    permitted: { default: null },
  });
  const { admin } = useSession(),
    { preview } = useOperations(),
    error = ref('');
  const allowed = computed(
    () =>
      admin.value &&
      !installationOwned(props.row) &&
      (props.permitted === null ? owned(props.row) : props.permitted)
  );
  const reason = computed(() =>
    !admin.value
      ? '当前账号只有查看权限'
      : installationOwned(props.row)
      ? '安装任务容器由任务工作流管理'
      : !allowed.value
      ? '外部或受保护容器，未授权直接变更'
      : ''
  );
  async function operate(action) {
    try {
      error.value = '';
      await preview(props.row.nodeId, { action: 'container.' + action, id: props.row.Id });
    } catch (e) {
      error.value = e.message;
    }
  }
</script>
