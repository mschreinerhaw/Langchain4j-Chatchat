<template>
  <div class="toolbar">
    <select v-model="node" aria-label="筛选主机">
      <option value="">全部主机</option>
      <option v-for="n in nodes" :key="n.id" :value="n.id">{{ n.name }}</option>
    </select>
    <select v-model="state" aria-label="筛选状态">
      <option value="">全部状态</option>
      <option value="running">运行中</option>
      <option value="exited">已退出</option>
      <option value="created">已创建</option>
    </select>
    <button v-if="admin" class="primary" @click="creating = !creating">
      {{ creating ? '收起创建表单' : '＋ 创建容器' }}
    </button>
    <button v-if="admin" @click="creatingVolume = !creatingVolume">创建数据卷</button>
  </div>
  <CreateVolumeForm
    v-if="creatingVolume"
    @cancel="creatingVolume = false"
    @planned="creatingVolume = false"
  />
  <CreateContainerForm v-if="creating" @cancel="creating = false" @planned="creating = false" />
  <PanelCard
    title="容器资源"
    description="现有业务容器只读发现；签名应用的组件通过应用工作流管理。"
  >
    <ContainerTable :rows="filtered" />
  </PanelCard>
</template>
<script setup>
  import { ref, computed } from 'vue';
  import { useCluster } from '../../stores/cluster';
  import { useSession } from '../../stores/session';
  import PanelCard from '../../components/ui/PanelCard.vue';
  import ContainerTable from '../../components/containers/ContainerTable.vue';
  import CreateContainerForm from '../../components/containers/CreateContainerForm.vue';
  import CreateVolumeForm from '../../components/containers/CreateVolumeForm.vue';
  const { nodes, containers } = useCluster(),
    { admin } = useSession(),
    node = ref(''),
    state = ref(''),
    creating = ref(false),
    creatingVolume = ref(false);
  const filtered = computed(() =>
    containers.value.filter(
      (c) =>
        (!node.value || String(c.nodeId) === String(node.value)) &&
        (!state.value || c.State === state.value)
    )
  );
</script>
