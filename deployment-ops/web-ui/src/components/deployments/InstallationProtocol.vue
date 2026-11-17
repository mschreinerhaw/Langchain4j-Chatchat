<template>
  <details v-if="protocol" class="notice">
    <summary>查看安装协议 · {{ protocol.name }} / {{ protocol.version }}</summary>
    <p>以下为已签名的安装定义，密码将在参数页填写。命令按容器参数数组执行。</p>
    <p v-if="protocol.runtimeInstaller" class="muted">
      Native Runtime
      使用专用产品安装器；下表未声明的依赖和启动命令由该受信适配器管理，不代表空依赖。
    </p>
    <DataTable :rows="rows" :columns="columns" :searchable="false" />
  </details>
</template>
<script setup>
  import { computed } from 'vue';
  import DataTable from '../ui/DataTable.vue';
  const props = defineProps({ protocol: Object });
  const columns = [
    { key: 'id', label: '组件' },
    { key: 'image', label: '固定镜像 ID' },
    { key: 'dependencies', label: '启动依赖' },
    { key: 'command', label: '启动命令 / 参数' },
    { key: 'health', label: '就绪条件' },
  ];
  const rows = computed(() =>
    (props.protocol?.components || []).map((c) => ({
      id: c.id,
      image: props.protocol.images?.[c.image]?.id,
      dependencies: (c.dependsOn || []).join(' → ') || '未声明',
      command:
        JSON.stringify([...(c.entrypoint || []), ...(c.command || [])]) === '[]'
          ? '使用固定镜像的 ENTRYPOINT / CMD'
          : JSON.stringify({ entrypoint: c.entrypoint, command: c.command }),
      health: c.health?.type || c.kind,
    }))
  );
</script>
